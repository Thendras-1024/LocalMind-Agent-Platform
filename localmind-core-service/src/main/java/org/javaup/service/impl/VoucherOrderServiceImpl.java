package org.javaup.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.dto.CancelVoucherOrderDto;
import org.javaup.dto.GetVoucherOrderByVoucherIdDto;
import org.javaup.dto.GetVoucherOrderDto;
import org.javaup.dto.Result;
import org.javaup.dto.VoucherReconcileLogDto;
import org.javaup.entity.SeckillVoucher;
import org.javaup.entity.UserInfo;
import org.javaup.entity.Voucher;
import org.javaup.entity.VoucherOrder;
import org.javaup.entity.VoucherOrderRouter;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessType;
import org.javaup.enums.LogType;
import org.javaup.enums.OrderStatus;
import org.javaup.enums.SeckillVoucherOrderOperate;
import org.javaup.exception.HmdpFrameException;
import org.javaup.kafka.message.SeckillVoucherMessage;
import org.javaup.kafka.producer.SeckillVoucherProducer;
import org.javaup.kafka.redis.RedisVoucherData;
import org.javaup.lua.SeckillVoucherDomain;
import org.javaup.lua.SeckillVoucherOperate;
import org.javaup.mapper.VoucherOrderMapper;
import org.javaup.mapper.VoucherOrderRouterMapper;
import org.javaup.message.MessageExtend;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.redis.RedisCacheImpl;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.repeatexecutelimit.annotion.RepeatExecuteLimit;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IUserInfoService;
import org.javaup.service.IVoucherOrderRouterService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherReconcileLogService;
import org.javaup.service.IVoucherService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.javaup.utils.RedisIdWorker;
import org.javaup.utils.UserHolder;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.javaup.constant.Constant.SECKILL_VOUCHER_TOPIC;
import static org.javaup.constant.RepeatExecuteLimitConstants.SECKILL_VOUCHER_ORDER;

/**

 **/
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private IVoucherService voucherService;
    
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    
    @Resource
    private SeckillVoucherOperate seckillVoucherOperate;
    
    @Resource
    private SeckillVoucherProducer seckillVoucherProducer;
    
    @Resource
    private RedisCacheImpl redisCache;
    
    @Resource
    private IVoucherOrderRouterService voucherOrderRouterService;
    
    @Resource
    private IUserInfoService userInfoService;
    
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    
    @Resource
    private VoucherOrderRouterMapper voucherOrderRouterMapper;
    
    @Resource
    private RedisVoucherData redisVoucherData;
    
    @Resource
    private IVoucherReconcileLogService voucherReconcileLogService;
    

    /* ========== 以下为旧版本代码，已被Kafka异步架构替代，不再使用 ========== */

    // private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // static {
    //     SECKILL_SCRIPT = new DefaultRedisScript<>();
    //     SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
    //     SECKILL_SCRIPT.setResultType(Long.class);
    // }

    /**
     * 秒杀订单线程池 - 用于处理订单创建后的异步任务（如统计、通知等）
     * 注意：实际订单创建是通过Kafka异步消费，不是这个线程池
     */
    public static final ThreadPoolExecutor SECKILL_ORDER_EXECUTOR =
            new ThreadPoolExecutor(
                    1,                      // 核心线程数
                    1,                      // 最大线程数
                    0L,                     // 线程空闲存活时间
                    TimeUnit.MILLISECONDS,  // 时间单位
                    new LinkedBlockingQueue<>(1024),  // 阻塞队列容量
                    new NamedThreadFactory("seckill-order-", false),  // 线程工厂
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
    
    
    @PostConstruct
    private void init(){
        // 这是黑马点评的普通版本，升级版本中不再使用此方式
        // ===== V1旧版本：Redis Stream异步处理，已被Kafka替代 =====
        // SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    private void destroy(){
        try {
            SECKILL_ORDER_EXECUTOR.shutdown();
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                SECKILL_ORDER_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SECKILL_ORDER_EXECUTOR.shutdownNow();
        }
    }

    /* ========== V1旧版本：Redis Stream异步处理，已被Kafka替代 ==========
    private class VoucherOrderHandler implements Runnable{
        private final String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 0.初始化stream
                    initStream();
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        public void initStream(){
            Boolean exists = stringRedisTemplate.hasKey(queueName);
            if (BooleanUtil.isFalse(exists)) {
                log.info("stream不存在，开始创建stream");
                // 不存在，需要创建
                stringRedisTemplate.opsForStream().createGroup(queueName, ReadOffset.latest(), "g1");
                log.info("stream和group创建完毕");
                return;
            }
            // stream存在，判断group是否存在
            StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream().groups(queueName);
            if(groups.isEmpty()){
                log.info("group不存在，开始创建group");
                // group不存在，创建group
                stringRedisTemplate.opsForStream().createGroup(queueName, ReadOffset.latest(), "g1");
                log.info("group创建完毕");
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            // 获取代理对象（事务）
            createVoucherOrderV1(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }
    ========== V1旧版本结束 ==========*/

    IVoucherOrderService proxy;
    /**
     * 抢优惠券下单
     * */
    @Override
    public Result<Long> seckillVoucher(Long voucherId) {
        //return doSeckillVoucherV1(voucherId);
        return doSeckillVoucherV2(voucherId);
    }
    /**
     * 旧版本下单
     * */
    // public Result<Long> doSeckillVoucherV1(Long voucherId) {
    //     Long userId = UserHolder.getUser().getId();
    //     long orderId = snowflakeIdGenerator.nextId();
    //     // 1.执行lua脚本
    //     Long result = stringRedisTemplate.execute(
    //             SECKILL_SCRIPT,
    //             Collections.emptyList(),
    //             voucherId.toString(), userId.toString(), String.valueOf(orderId)
    //     );
    //     int r = result.intValue();
    //     // 2.判断结果是否为0
    //     if (r != 0) {
    //         // 2.1.不为0 ，代表没有购买资格
    //         return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
    //     }
    //     // 3.获取代理对象
    //     proxy = (IVoucherOrderService) AopContext.currentProxy();
    //     // 4.返回订单id
    //     return Result.ok(orderId);
    // }

    /**
     * V2版本秒杀下单 - 异步架构
     * 流程：用户请求 → Lua扣减Redis库存 → 发送MQ消息 → 立即返回订单号 → MQ消费者异步创建订单
     */
    public Result<Long> doSeckillVoucherV2(Long voucherId) {
        // 第1步：查询秒杀券详情（多级缓存：Caffeine → Redis → DB）
        SeckillVoucherFullModel seckillVoucherFullModel = seckillVoucherService.queryByVoucherId(voucherId);

        // 第2步：加载库存到Redis（如果Redis中没有库存的话）
        seckillVoucherService.loadVoucherStock(voucherId);

        // 第3步：获取当前登录用户ID
        Long userId = UserHolder.getUser().getId();

        // 第4步：校验用户等级是否满足秒杀券的参与条件
        verifyUserLevel(seckillVoucherFullModel, userId);

        // 第5步：生成雪花ID（订单ID和追踪ID全局唯一）
        long orderId = snowflakeIdGenerator.nextId();  // 订单ID
        long traceId = snowflakeIdGenerator.nextId();   // 追踪ID（用于对账/问题排查）

        // 第6步：构建Redis的Key列表（库存Key、用户购买记录Key、追踪日志Key）
        // 这些Key使用HashTag确保在同一个Redis分片，便于原子操作
        List<String> keys = ListUtil.of(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId).getRelKey(),       // 库存Key
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId).getRelKey(),        // 已购用户Set Key
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_TRACE_LOG_TAG_KEY, voucherId).getRelKey()     // 追踪日志 Key
        );

        // 第7步：构建Lua脚本参数
        String[] args = new String[9];
        args[0] = voucherId.toString();                                        // 优惠券ID
        args[1] = userId.toString();                                            // 用户ID
        args[2] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getBeginTime())); // 秒杀开始时间
        args[3] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getEndTime()));     // 秒杀结束时间
        args[4] = String.valueOf(seckillVoucherFullModel.getStatus());         // 优惠券状态
        args[5] = String.valueOf(orderId);                                      // 订单ID
        args[6] = String.valueOf(traceId);                                      // 追踪ID
        args[7] = String.valueOf(LogType.DEDUCT.getCode());                    // 日志类型（扣减）
        // TTL = 秒杀结束时间 + 1天（确保秒杀结束后追踪日志还能保留一段时间用于对账）
        long secondsUntilEnd = Duration.between(LocalDateTimeUtil.now(), seckillVoucherFullModel.getEndTime()).getSeconds();
        long ttlSeconds = Math.max(1L, secondsUntilEnd + Duration.ofDays(1).getSeconds());
        args[8] = String.valueOf(ttlSeconds);

        // 第8步：执行Lua脚本（原子化完成：时间校验 + 库存检查 + 一人一单校验 + 扣减库存）
        SeckillVoucherDomain seckillVoucherDomain = seckillVoucherOperate.execute(keys, args);
        if (!seckillVoucherDomain.getCode().equals(BaseCode.SUCCESS.getCode())) {
            // Lua脚本返回非0值（库存不足/重复下单/时间不符合等）
            throw new HmdpFrameException(Objects.requireNonNull(BaseCode.getRc(seckillVoucherDomain.getCode())));
        }

        // 第9步：构建MQ消息（携带扣减前后的库存数量，用于后续对账）
        SeckillVoucherMessage seckillVoucherMessage = new SeckillVoucherMessage(
                userId,                                // 用户ID
                voucherId,                             // 优惠券ID
                orderId,                               // 订单ID
                traceId,                               // 追踪ID
                seckillVoucherDomain.getBeforeQty(),  // 扣减前库存
                seckillVoucherDomain.getDeductQty(),   // 本次扣减数量
                seckillVoucherDomain.getAfterQty(),    // 扣减后库存
                Boolean.FALSE                          // 是否为自动发券（false=用户主动下单）
        );

        // 第10步：发送MQ消息到Kafka（异步创建订单）
        seckillVoucherProducer.sendPayload(
                SpringUtil.getPrefixDistinctionName() + "-" + SECKILL_VOUCHER_TOPIC,
                seckillVoucherMessage);

        // 第11步：立即返回订单号给用户（订单实际还没创建，但用户可以拿到订单号查询状态）
        return Result.ok(orderId);
    }
    
    /**
     * 校验用户等级是否满足秒杀券的参与条件
     * 秒杀券可能设置了会员等级限制（allowedLevels白名单 或 minLevel最低等级）
     *
     * @param seckillVoucherFullModel 秒杀券详情
     * @param userId 用户ID
     */
    public void verifyUserLevel(SeckillVoucherFullModel seckillVoucherFullModel, Long userId) {
        // 获取秒杀券的等级规则
        String allowedLevelsStr = seckillVoucherFullModel.getAllowedLevels();  // 白名单等级，如"1,2,3"
        Integer minLevel = seckillVoucherFullModel.getMinLevel();              // 最低等级要求

        // 如果没有设置任何等级规则，直接放行
        boolean hasLevelRule = StrUtil.isNotBlank(allowedLevelsStr) || Objects.nonNull(minLevel);
        if (!hasLevelRule) {
            return;
        }

        // 根据userId查询用户信息
        UserInfo userInfo = userInfoService.getByUserId(userId);
        if (Objects.isNull(userInfo)) {
            throw new HmdpFrameException(BaseCode.USER_NOT_EXIST);
        }

        boolean allowed = true;
        Integer level = userInfo.getLevel();

        // 规则1：检查是否在白名单中
        if (StrUtil.isNotBlank(allowedLevelsStr)) {
            try {
                // 解析逗号分隔的白名单等级
                Set<Integer> allowedLevels = Arrays.stream(allowedLevelsStr.split(","))
                        .map(String::trim)                   // 去空格
                        .filter(StrUtil::isNotBlank)        // 过滤空字符串
                        .map(Integer::valueOf)              // 转为整数
                        .collect(Collectors.toSet());       // 放入Set
                if (CollectionUtil.isNotEmpty(allowedLevels)) {
                    // 检查用户等级是否在白名单中
                    allowed = allowedLevels.contains(level);
                }
            } catch (Exception parseEx) {
                log.warn("allowedLevels 解析失败, voucherId={}, raw={}",
                        seckillVoucherFullModel.getVoucherId(),
                        allowedLevelsStr, parseEx);
            }
        }

        // 规则2：检查是否满足最低等级要求
        if (allowed && Objects.nonNull(minLevel)) {
            allowed = Objects.nonNull(level) && level >= minLevel;
        }

        // 等级校验不通过，抛出异常
        if (!allowed) {
            throw new HmdpFrameException("当前会员级别不满足参与条件");
        }
    }

   
    private static class AudienceRule {
        public Set<Integer> allowedLevels;
        public Integer minLevel;
        public Set<String> allowedCities;
        
        boolean hasLevelRule(){
            return (allowedLevels != null && !allowedLevels.isEmpty()) || minLevel != null;
        }
        boolean hasCityRule(){
            return allowedCities != null && !allowedCities.isEmpty();
        }
    }

    /* ========== V1旧版本：同步下单，已被Kafka异步架构替代 ==========
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrderV1(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = voucherOrder.getUserId();

        // 5.1.查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                // set stock = stock - 1
                .setSql("stock = stock - 1")
                // where id = ? and stock > 0
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }
        // 7.创建订单
        save(voucherOrder);
    }
    ========== V1旧版本结束 ==========*/
    
    
    /**
     * RocketMQ消费者回调：创建订单（异步）
     * 幂等保障：@RepeatExecuteLimit分布式锁 + 数据库查询订单是否存在
     *
     * @param message MQ消息，包含用户ID、优惠券ID、订单ID等
     * @return true=创建成功
     */
    @Override
    @RepeatExecuteLimit(name = SECKILL_VOUCHER_ORDER, keys = {"#message.uuid"})  // 分布式锁 + 幂等检查
    @Transactional(rollbackFor = Exception.class)  // 事务保证原子性
    public boolean createVoucherOrderV2(MessageExtend<SeckillVoucherMessage> message) {
        // 解析MQ消息体
        SeckillVoucherMessage messageBody = message.getMessageBody();
        Long userId = messageBody.getUserId();

        // 第1步：幂等检查 - 查询是否已存在有效订单（防止消息重复消费）
        VoucherOrder normalVoucherOrder = lambdaQuery()
                .eq(VoucherOrder::getVoucherId, messageBody.getVoucherId())    // 优惠券ID
                .eq(VoucherOrder::getUserId, userId)                          // 用户ID
                .eq(VoucherOrder::getStatus, OrderStatus.NORMAL.getCode())    // 正常状态的订单
                .one();
        if (Objects.nonNull(normalVoucherOrder)) {
            // 订单已存在，说明消息被重复消费了，直接返回（幂等）
            log.warn("已存在此订单，voucherId：{},userId：{}", normalVoucherOrder.getVoucherId(), userId);
            throw new HmdpFrameException(BaseCode.VOUCHER_ORDER_EXIST);
        }

        // 第2步：扣减数据库库存（乐观锁：stock > 0）
        // 注意：Redis库存已在用户下单时扣减，这里是确保DB库存一致
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")                    // SQL：stock = stock - 1
                .eq("voucher_id", messageBody.getVoucherId())  // WHERE voucher_id = ?
                .gt("stock", 0)                                // AND stock > 0（乐观锁）
                .update();
        if (!success) {
            throw new HmdpFrameException("优惠券库存不足！优惠券id:" + messageBody.getVoucherId());
        }

        // 第3步：创建订单记录
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(messageBody.getOrderId());           // 使用消息中预生成的订单ID
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(messageBody.getVoucherId());
        voucherOrder.setCreateTime(LocalDateTimeUtil.now());
        save(voucherOrder);

        // 第4步：创建订单路由记录（用于按订单ID查询时定位分片）
        VoucherOrderRouter voucherOrderRouter = new VoucherOrderRouter();
        voucherOrderRouter.setId(snowflakeIdGenerator.nextId());
        voucherOrderRouter.setOrderId(voucherOrder.getId());
        voucherOrderRouter.setUserId(userId);
        voucherOrderRouter.setVoucherId(voucherOrder.getVoucherId());
        voucherOrderRouter.setCreateTime(LocalDateTimeUtil.now());
        voucherOrderRouter.setUpdateTime(LocalDateTimeUtil.now());
        voucherOrderRouterService.save(voucherOrderRouter);

        // 第5步：写入Redis缓存（60秒TTL），加速后续订单查询
        redisCache.set(RedisKeyBuild.createRedisKey(
                RedisKeyManage.DB_SECKILL_ORDER_KEY, messageBody.getOrderId()),
                voucherOrder,
                60,
                TimeUnit.SECONDS
        );

        // 第6步：记录对账日志（用于后续数据一致性校验）
        voucherReconcileLogService.saveReconcileLog(
                LogType.DEDUCT.getCode(),
                BusinessType.SUCCESS.getCode(),
                "order created",
                message
        );
        return true;
    }
    
    @Override
    public Long getSeckillVoucherOrder(GetVoucherOrderDto getVoucherOrderDto) {
        VoucherOrder voucherOrder = 
                redisCache.get(RedisKeyBuild.createRedisKey(
                        RedisKeyManage.DB_SECKILL_ORDER_KEY, 
                        getVoucherOrderDto.getOrderId()), 
                        VoucherOrder.class);
        if (Objects.nonNull(voucherOrder)) {
            return voucherOrder.getId();
        }
        VoucherOrderRouter voucherOrderRouter = 
                voucherOrderRouterService.lambdaQuery()
                        .eq(VoucherOrderRouter::getOrderId, getVoucherOrderDto.getOrderId())
                        .one();
        if (Objects.nonNull(voucherOrderRouter)) {
            return voucherOrderRouter.getOrderId();
        }
        return null;
    }
    
    @Override
    public Long getSeckillVoucherOrderIdByVoucherId(GetVoucherOrderByVoucherIdDto getVoucherOrderByVoucherIdDto) {
        VoucherOrder voucherOrder = lambdaQuery()
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
                .eq(VoucherOrder::getVoucherId, getVoucherOrderByVoucherIdDto.getVoucherId())
                .eq(VoucherOrder::getStatus, OrderStatus.NORMAL.getCode())
                .one();
        if (Objects.nonNull(voucherOrder)) {
            return voucherOrder.getId();
        }
        return null;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean cancel(CancelVoucherOrderDto cancelVoucherOrderDto) {
        VoucherOrder voucherOrder = lambdaQuery()
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
                .eq(VoucherOrder::getVoucherId, cancelVoucherOrderDto.getVoucherId())
                .eq(VoucherOrder::getStatus, OrderStatus.NORMAL.getCode())
                .one();
        if (Objects.isNull(voucherOrder)) {
            throw new HmdpFrameException(BaseCode.SECKILL_VOUCHER_ORDER_NOT_EXIST);
        }
        SeckillVoucher seckillVoucher = seckillVoucherService.lambdaQuery()
                .eq(SeckillVoucher::getVoucherId, cancelVoucherOrderDto.getVoucherId())
                .one();
        if (Objects.isNull(seckillVoucher)) {
            throw new HmdpFrameException(BaseCode.SECKILL_VOUCHER_NOT_EXIST);
        }
        boolean updateResult = lambdaUpdate().set(VoucherOrder::getStatus, OrderStatus.CANCEL.getCode())
                .set(VoucherOrder::getUpdateTime, LocalDateTimeUtil.now())
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
                .eq(VoucherOrder::getVoucherId, cancelVoucherOrderDto.getVoucherId())
                .update();
        long traceId = snowflakeIdGenerator.nextId();
        VoucherReconcileLogDto voucherReconcileLogDto = new VoucherReconcileLogDto();
        voucherReconcileLogDto.setOrderId(voucherOrder.getId());
        voucherReconcileLogDto.setUserId(voucherOrder.getUserId());
        voucherReconcileLogDto.setVoucherId(voucherOrder.getVoucherId());
        voucherReconcileLogDto.setDetail("cancel voucher order ");
        voucherReconcileLogDto.setBeforeQty(seckillVoucher.getStock());
        voucherReconcileLogDto.setChangeQty(1);
        voucherReconcileLogDto.setAfterQty(seckillVoucher.getStock() + 1);
        voucherReconcileLogDto.setTraceId(traceId);
        voucherReconcileLogDto.setLogType(LogType.RESTORE.getCode());
        voucherReconcileLogDto.setBusinessType( BusinessType.CANCEL.getCode());
        boolean saveReconcileLogResult = voucherReconcileLogService.saveReconcileLog(voucherReconcileLogDto);
        
        boolean rollbackStockResult = seckillVoucherService.rollbackStock(cancelVoucherOrderDto.getVoucherId());
        
        Boolean result = updateResult && saveReconcileLogResult && rollbackStockResult;
        if (result) {
            redisVoucherData.rollbackRedisVoucherData(
                    SeckillVoucherOrderOperate.YES,
                    traceId,
                    voucherOrder.getVoucherId(),
                    voucherOrder.getUserId(),
                    voucherOrder.getId(),
                    seckillVoucher.getStock(),
                    1,
                    seckillVoucher.getStock() + 1
            );
            redisCache.delForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_STATUS_TAG_KEY, 
                    cancelVoucherOrderDto.getVoucherId()),
                    String.valueOf(voucherOrder.getUserId()));
            Voucher voucher = voucherService.getById(voucherOrder.getVoucherId());
            if (Objects.nonNull(voucher)) {
                String day = voucherOrder.getCreateTime().format(DateTimeFormatter.BASIC_ISO_DATE);
                RedisKeyBuild dailyKey = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.SECKILL_SHOP_TOP_BUYERS_DAILY_TAG_KEY,
                        voucher.getShopId(),
                        day
                );
                redisCache.incrementScoreForSortedSet(dailyKey, String.valueOf(voucherOrder.getUserId()), -1.0);
            }
            
            try {
                autoIssueVoucherToEarliestSubscriber(
                        voucherOrder.getVoucherId(), 
                        voucherOrder.getUserId()
                );
            } catch (Exception e) {
                log.warn("自动发券失败，voucherId={}, err=\n{}", voucherOrder.getVoucherId(), e.getMessage());
            }
        }
        return result;
    }
    
    @Override
    public boolean autoIssueVoucherToEarliestSubscriber(final Long voucherId, final Long excludeUserId) {
        SeckillVoucherFullModel seckillVoucherFullModel = seckillVoucherService.queryByVoucherId(voucherId);
        if (Objects.isNull(seckillVoucherFullModel) 
                || 
                Objects.isNull(seckillVoucherFullModel.getBeginTime()) 
                ||
                Objects.isNull(seckillVoucherFullModel.getEndTime())) {
            return false;
        }
        seckillVoucherService.loadVoucherStock(voucherId);
        String candidateUserIdStr = findEarliestCandidate(voucherId, excludeUserId);
        if (StrUtil.isBlank(candidateUserIdStr)) {
            return false;
        }
        return issueToCandidate(voucherId, candidateUserIdStr, seckillVoucherFullModel);
    }
    
    private String findEarliestCandidate(final Long voucherId, final Long excludeUserId) {
        RedisKeyBuild subscribeZSetKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_ZSET_TAG_KEY, voucherId);
        RedisKeyBuild purchasedSetKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId);
        
        final long pageCount = 1L;
        long offset = 0L;
        while (true) {
            Set<ZSetOperations.TypedTuple<String>> page = redisCache.rangeByScoreWithScoreForSortedSet(
                    subscribeZSetKey,
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                    offset,
                    pageCount,
                    String.class
            );
            if (CollectionUtil.isEmpty(page)) {
                return null;
            }
            ZSetOperations.TypedTuple<String> tuple = page.iterator().next();
            if (Objects.isNull(tuple) || Objects.isNull(tuple.getValue())) {
                offset++;
                continue;
            }
            String uidStr = tuple.getValue();
            if (StrUtil.isBlank(uidStr)) {
                offset++;
                continue;
            }
            if (Objects.nonNull(excludeUserId) && Objects.equals(uidStr, String.valueOf(excludeUserId))) {
                offset++;
                continue;
            }
            Boolean purchased = redisCache.isMemberForSet(purchasedSetKey, uidStr);
            if (BooleanUtil.isTrue(purchased)) {
                offset++;
                continue;
            }
            return uidStr;
        }
    }
    
    private boolean issueToCandidate(final Long voucherId, 
                                     final String candidateUserIdStr, 
                                     final SeckillVoucherFullModel seckillVoucherFullModel) {
        Long candidateUserId = Long.valueOf(candidateUserIdStr);
        try {
            verifyUserLevel(seckillVoucherFullModel, candidateUserId);
        } catch (Exception e) {
            log.info("候选用户不满足人群规则，自动发券跳过。voucherId={}, userId={}", voucherId, candidateUserId);
            return false;
        }
        List<String> keys = buildSeckillKeys(voucherId);
        long orderId = snowflakeIdGenerator.nextId();
        long traceId = snowflakeIdGenerator.nextId();
        String[] args = buildSeckillArgs(voucherId, candidateUserIdStr, seckillVoucherFullModel, orderId, traceId);
        SeckillVoucherDomain domain = seckillVoucherOperate.execute(keys, args);
        if (!Objects.equals(domain.getCode(), BaseCode.SUCCESS.getCode())) {
            log.info("自动发券Lua扣减失败，code={}, voucherId={}, userId={}", domain.getCode(), voucherId, candidateUserId);
            return false;
        }
        SeckillVoucherMessage message = new SeckillVoucherMessage(
                candidateUserId,
                voucherId,
                orderId,
                traceId,
                domain.getBeforeQty(),
                domain.getDeductQty(),
                domain.getAfterQty(),
                Boolean.TRUE
        );
        seckillVoucherProducer.sendPayload(
                SpringUtil.getPrefixDistinctionName() + "-" + SECKILL_VOUCHER_TOPIC,
                message
        );
        return true;
    }
    
    private List<String> buildSeckillKeys(final Long voucherId) {
        String stockKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId).getRelKey();
        String userKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId).getRelKey();
        String traceKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_TRACE_LOG_TAG_KEY, voucherId).getRelKey();
        return ListUtil.of(stockKey, userKey, traceKey);
    }
    
    private String[] buildSeckillArgs(final Long voucherId,
                                      final String userIdStr,
                                      final SeckillVoucherFullModel seckillVoucherFullModel,
                                      final long orderId,
                                      final long traceId) {
        String[] args = new String[9];
        args[0] = voucherId.toString();
        args[1] = userIdStr;
        args[2] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getBeginTime()));
        args[3] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getEndTime()));
        args[4] = String.valueOf(seckillVoucherFullModel.getStatus());
        args[5] = String.valueOf(orderId);
        args[6] = String.valueOf(traceId);
        args[7] = String.valueOf(LogType.DEDUCT.getCode());
        args[8] = String.valueOf(computeTtlSeconds(seckillVoucherFullModel));
        return args;
    }
    
    private long computeTtlSeconds(final SeckillVoucherFullModel seckillVoucherFullModel) {
        long secondsUntilEnd = Duration.between(LocalDateTimeUtil.now(), seckillVoucherFullModel.getEndTime()).getSeconds();
        return Math.max(1L, secondsUntilEnd + Duration.ofDays(1).getSeconds());
    }

}
