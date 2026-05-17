package org.javaup.kafka.consumer;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.javaup.consumer.AbstractConsumerHandler;
import org.javaup.core.RedisKeyManage;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessType;
import org.javaup.enums.LogType;
import org.javaup.enums.SeckillVoucherOrderOperate;
import org.javaup.exception.HmdpFrameException;
import org.javaup.kafka.message.SeckillVoucherMessage;
import org.javaup.kafka.redis.RedisVoucherData;
import org.javaup.message.MessageExtend;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IAutoIssueNotifyService;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherReconcileLogService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.javaup.constant.Constant.SECKILL_VOUCHER_TOPIC;
import static org.javaup.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;



@Slf4j
@Component
public class SeckillVoucherConsumer extends AbstractConsumerHandler<SeckillVoucherMessage> {

    /**
     * 消息延迟阈值：10秒
     * 如果消息从生产到消费的延迟超过此阈值，说明消息已过期，需要丢弃并回滚Redis
     */
    public static Long MESSAGE_DELAY_TIME = 10000L;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedisVoucherData redisVoucherData;

    @Resource
    private RedisCache redisCache;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherReconcileLogService voucherReconcileLogService;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;


    @Resource
    private IAutoIssueNotifyService autoIssueNotifyService;


    /**
     * 消费后异步任务线程池
     * 用于处理非核心任务：清理订阅信息、发送通知、统计Top买家
     * 线程数 = CPU核心数，最大队列容量 = 1024 × CPU核心数
     */
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();
    private static final int EXECUTOR_THREADS = Math.max(2, CPU_CORES);
    private static final int EXECUTOR_QUEUE_CAPACITY = 1024 * Math.max(1, CPU_CORES);

    private static final ThreadPoolExecutor SECKILL_ORDER_CONSUME_TASK_EXECUTOR =
            new ThreadPoolExecutor(
                    EXECUTOR_THREADS,                      // 核心线程数
                    EXECUTOR_THREADS,                      // 最大线程数
                    0L,                                    // 空闲存活时间
                    TimeUnit.MILLISECONDS,                 // 时间单位
                    new LinkedBlockingQueue<>(EXECUTOR_QUEUE_CAPACITY),  // 阻塞队列
                    new NamedThreadFactory("seckill-order-consume-task", false),  // 线程工厂
                    new ThreadPoolExecutor.CallerRunsPolicy()  // 拒绝策略：调用者执行
            );

    private static class NamedThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final boolean daemon;
        private final AtomicInteger index = new AtomicInteger(1);

        public NamedThreadFactory(String namePrefix, boolean daemon) {
            this.namePrefix = namePrefix;
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + index.getAndIncrement());
            t.setDaemon(daemon);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("未捕获异常，线程={}, err={}", thread.getName(), ex.getMessage(), ex)
            );
            return t;
        }
    }


    public SeckillVoucherConsumer() {
        super(SeckillVoucherMessage.class);
    }


    /**
     * RocketMQ消息监听器 - 接收秒杀券下单消息
     *
     * 消息处理流程：
     * 1. consumeRaw() 解析消息
     * 2. beforeConsume() 消息延迟检测
     * 3. doConsume() 执行业务逻辑（创建订单）
     * 4. afterConsumeSuccess() / afterConsumeFailure() 后置处理
     * 5. acknowledgment.acknowledge() 手动提交offset
     *
     * 注意：只有在业务方法正常执行完才会ack，否则消息会重试
     */
    @KafkaListener(
            topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + SECKILL_VOUCHER_TOPIC}
    )
    public void onMessage(String value,
                          @Headers Map<String, Object> headers,
                          @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                          Acknowledgment acknowledgment) {
        consumeRaw(value, key, headers);
        // 只有方法正常执行完才提交offset，失败不ack会重试
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }

    /**
     * 消费前置处理 - 消息延迟检测
     *
     * 如果消息从生产到消费的延迟超过10秒，说明系统可能出现严重延迟
     * 此时需要丢弃消息并回滚Redis库存，避免数据不一致
     *
     * @param message 消息体
     * @return true=继续消费，false=丢弃消息
     */
    @Override
    protected Boolean beforeConsume(MessageExtend<SeckillVoucherMessage> message) {
        // 计算消息延迟时间 = 当前时间 - 消息生产时间
        long producerTimeTimestamp = message.getProducerTime().getTime();
        long delayTime = System.currentTimeMillis() - producerTimeTimestamp;

        // 如果消息延迟超过阈值（10秒），丢弃消息并回滚Redis
        if (delayTime > MESSAGE_DELAY_TIME) {
            log.info("消费到RocketMQ的创建优惠券消息延迟时间大于了 {} 毫秒 此订单消息被丢弃 订单号 : {}",
                    delayTime, message.getMessageBody().getOrderId());

            // 生成追踪ID并回滚Redis库存
            long traceId = snowflakeIdGenerator.nextId();
            redisVoucherData.rollbackRedisVoucherData(
                    SeckillVoucherOrderOperate.YES,  // 需要回滚
                    traceId,
                    message.getMessageBody().getVoucherId(),
                    message.getMessageBody().getUserId(),
                    message.getMessageBody().getOrderId(),
                    // 回滚操作：扣减前和扣减后的数量要反过来
                    message.getMessageBody().getAfterQty(),    // 回滚前
                    message.getMessageBody().getChangeQty(),   // 扣减量
                    message.getMessageBody().getBeforeQty()   // 回滚后
            );

            // 记录超时对账日志
            try {
                voucherReconcileLogService.saveReconcileLog(LogType.RESTORE.getCode(),
                        BusinessType.TIMEOUT.getCode(),
                        "message delayed " + delayTime + "ms, rollback redis",
                        traceId,
                        message);
            } catch (Exception e) {
                log.warn("保存对账日志失败(延迟丢弃)", e);
            }
            return false;  // 丢弃消息
        }
        return true;  // 继续消费
    }

    /**
     * 消费核心逻辑 - 创建订单
     *
     * 调用订单服务创建订单
     * 订单创建的具体逻辑在 VoucherOrderServiceImpl.createVoucherOrderV2()
     *
     * @param message 消息体
     */
    @Override
    protected void doConsume(MessageExtend<SeckillVoucherMessage> message) {
        voucherOrderService.createVoucherOrderV2(message);
    }

    /**
     * 消费后置处理 - 成功时执行
     *
     * 订单创建成功后，异步执行以下任务：
     * 1. 清理用户订阅信息（从ZSET中移除）
     * 2. 发送自动发券通知（如有）
     * 3. 统计店铺Top买家
     *
     * @param message 消息体
     */
    @Override
    protected void afterConsumeSuccess(MessageExtend<SeckillVoucherMessage> message) {
        super.afterConsumeSuccess(message);
        SeckillVoucherMessage messageBody = message.getMessageBody();
        Long userId = messageBody.getUserId();
        Long voucherId = messageBody.getVoucherId();
        Long orderId = messageBody.getOrderId();

        // 异步执行非核心任务
        SECKILL_ORDER_CONSUME_TASK_EXECUTOR.execute(() -> {
            // ===== 任务1：清理用户订阅信息 =====
            try {
                // 从ZSET中删除用户的订阅记录
                RedisKeyBuild subscribeZSetKey = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.SECKILL_SUBSCRIBE_ZSET_TAG_KEY,
                        messageBody.getVoucherId()
                );
                redisCache.delForSortedSet(subscribeZSetKey, String.valueOf(userId));
            } catch (Exception e) {
                log.warn("清理订阅ZSET成员失败，voucherId={}, userId={}, err={}", messageBody.getVoucherId(), userId, e.getMessage());
            }

            // ===== 任务2：发送自动发券通知（如有） =====
            if (Boolean.TRUE.equals(messageBody.getAutoIssue())) {
                try {
                    autoIssueNotifyService.sendAutoIssueNotify(voucherId, userId, orderId);
                } catch (Exception e) {
                    log.warn("自动发券通知发送失败，voucherId={}, userId={}, orderId={}, err={}",
                            voucherId, userId, orderId, e.getMessage());
                }
            }

            // ===== 任务3：统计店铺Top买家 =====
            try {
                // 查询店铺ID
                SeckillVoucherFullModel voucherFull = seckillVoucherService.queryByVoucherId(voucherId);
                if (Objects.isNull(voucherFull)) {
                    return;
                }
                Long shopId = voucherFull.getShopId();

                // 获取当天日期（yyyyMMdd格式）
                String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

                // 构建店铺当日买家统计Key
                RedisKeyBuild dailyKey = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.SECKILL_SHOP_TOP_BUYERS_DAILY_TAG_KEY,
                        shopId,
                        day
                );

                // 用户购买次数+1（ZSET评分）
                redisCache.incrementScoreForSortedSet(dailyKey, String.valueOf(userId), 1.0);

                // 设置TTL（90天）
                Long ttl = redisCache.getExpire(dailyKey, TimeUnit.SECONDS);
                if (ttl == null || ttl < 0) {
                    redisCache.expire(dailyKey, 90, TimeUnit.DAYS);
                }
            } catch (Exception e) {
                log.warn("统计店铺Top买家失败，忽略不影响主流程", e);
            }
        });
    }

    /**
     * 消费后置处理 - 失败时执行
     *
     * 订单创建失败时，需要回滚Redis库存保证数据一致性
     * 并记录对账日志用于后续问题排查
     *
     * @param message 消息体
     * @param throwable 异常信息
     */
    @Override
    protected void afterConsumeFailure(final MessageExtend<SeckillVoucherMessage> message,
                                       final Throwable throwable) {
        super.afterConsumeFailure(message, throwable);

        // 判断是否需要回滚Redis
        // 如果是"订单已存在"异常（幂等拦截），不需要回滚库存和用户记录
        SeckillVoucherOrderOperate seckillVoucherOrderOperate = SeckillVoucherOrderOperate.YES;
        if (throwable instanceof HmdpFrameException hmdpFrameException) {
            if (Objects.nonNull(hmdpFrameException.getCode()) &&
                    hmdpFrameException.getCode().equals(BaseCode.VOUCHER_ORDER_EXIST.getCode())) {
                seckillVoucherOrderOperate = SeckillVoucherOrderOperate.NO;  // 不需要回滚
            }
        }

        // 生成追踪ID并回滚Redis
        long traceId = snowflakeIdGenerator.nextId();
        redisVoucherData.rollbackRedisVoucherData(
                seckillVoucherOrderOperate,  // 是否需要回滚
                traceId,
                message.getMessageBody().getVoucherId(),
                message.getMessageBody().getUserId(),
                message.getMessageBody().getOrderId(),
                message.getMessageBody().getAfterQty(),
                message.getMessageBody().getChangeQty(),
                message.getMessageBody().getBeforeQty()
        );

        // 记录失败对账日志
        try {
            String detail = throwable == null ? "consume failed" : ("consume failed: " + throwable.getMessage());
            voucherReconcileLogService.saveReconcileLog(LogType.RESTORE.getCode(),
                    BusinessType.FAIL.getCode(),
                    detail,
                    traceId,
                    message
            );
        } catch (Exception e) {
            log.warn("保存对账日志失败(消费失败)", e);
        }
    }
}
