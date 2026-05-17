package org.javaup.repeatexecutelimit.aspect;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.javaup.constant.LockInfoType;
import org.javaup.exception.HmdpFrameException;
import org.javaup.handle.RedissonDataHandle;
import org.javaup.locallock.LocalLockCache;
import org.javaup.lockinfo.LockInfoHandle;
import org.javaup.lockinfo.factory.LockInfoHandleFactory;
import org.javaup.repeatexecutelimit.annotion.RepeatExecuteLimit;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.ServiceLocker;
import org.javaup.servicelock.factory.ServiceLockFactory;

import static org.javaup.repeatexecutelimit.constant.RepeatExecuteLimitConstant.PREFIX_NAME;
import static org.javaup.repeatexecutelimit.constant.RepeatExecuteLimitConstant.SUCCESS_FLAG;


/**
 * 幂等执行限制切面
 *
 * <p>核心功能：防止MQ消息被重复消费，确保接口幂等性
 *
 * <p>适用场景：
 * <ul>
 *   <li>MQ消费者处理消息时，防止消息被重复投递导致重复执行</li>
 *   <li>任何需要保证"同一条消息/请求只处理一次"的场景</li>
 * </ul>
 *
 * <p>实现原理：三级保险
 * <ol>
 *   <li>第一级：Redis标记检查（快速失败）</li>
 *   <li>第二级：本地锁（阻挡同JVM内多线程并发）</li>
 *   <li>第三级：分布式锁（阻挡多实例并发 + 双重Redis检查）</li>
 * </ol>
 *
 * @author 阿星不是程序员
 */
@Slf4j
@Aspect
@Order(-11)  // 确保在事务切面之前执行，避免锁拿到了但事务还没提交
@AllArgsConstructor
public class RepeatExecuteLimitAspect {

    /** 本地锁缓存，同JVM内多线程竞争时快速失败 */
    private final LocalLockCache localLockCache;

    /** 锁信息处理器工厂，生成锁名称 */
    private final LockInfoHandleFactory lockInfoHandleFactory;

    /** 分布式锁工厂，获取Redisson分布式锁 */
    private final ServiceLockFactory serviceLockFactory;

    /** Redisson数据操作句柄，操作Redis中的幂等标记 */
    private final RedissonDataHandle redissonDataHandle;


    /**
     * 环绕通知：执行幂等检查流程
     *
     * <p>完整执行流程：
     * <pre>
     * 1. 检查Redis幂等标记（key是否存在）
     *    └─ 存在 → 直接抛异常，拒绝重复执行
     *    └─ 不存在 → 继续
     *
     * 2. 获取本地锁（ReentrantLock）
     *    └─ 获取失败 → 直接抛异常
     *    └─ 获取成功 → 继续
     *
     * 3. 获取分布式锁（Redisson FairLock）
     *    └─ 获取失败 → 直接抛异常
     *    └─ 获取成功 → 继续
     *
     * 4. 再次检查Redis幂等标记（双重检查）
     *    └─ 已存在 → 抛异常（防止等待锁期间被其他实例插入）
     *    └─ 不存在 → 继续
     *
     * 5. 执行业务逻辑（joinPoint.proceed()）
     *
     * 6. 写入Redis幂等标记（durationTime秒内有效）
     *
     * 7. 释放分布式锁 → 释放本地锁
     * </pre>
     *
     * @param joinPoint 切入点，包含目标方法信息
     * @param repeatLimit 幂等注解，包含锁名、key、过期时间等配置
     * @return 业务方法返回值
     * @throws Throwable 业务异常或重复执行异常
     */
    @Around("@annotation(repeatLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RepeatExecuteLimit repeatLimit) throws Throwable {
        // ===== 注解配置 =====
        long durationTime = repeatLimit.durationTime();  // Redis标记过期时间（秒）
        String message = repeatLimit.message();           // 重复执行时的错误信息

        Object obj;
        LockInfoHandle lockInfoHandle = lockInfoHandleFactory.getLockInfoHandle(LockInfoType.REPEAT_EXECUTE_LIMIT);

        // ===== 构建锁名和幂等标记key =====
        // 锁名格式：PREFIX_NAME + 锁标识名 + keys（如 "repeat_execute_limit:seckill_voucher_order:uuid-xxx"）
        String lockName = lockInfoHandle.getLockName(joinPoint, repeatLimit.name(), repeatLimit.keys());
        String repeatFlagName = PREFIX_NAME + lockName;  // Redis中存储成功标记的key

        // ===== 第一级检查：快速判断是否已执行过 =====
        // 从Redis获取标记，如果存在说明之前已经执行成功过了
        String flagObject = redissonDataHandle.get(repeatFlagName);
        if (SUCCESS_FLAG.equals(flagObject)) {
            // 已存在成功标记，说明这个消息/请求已经处理过了
            // 抛出异常而不是直接返回，是因为调用方需要感知到"重复"
            log.warn("幂等拦截：检测到重复执行，lockName={}, message={}", lockName, message);
            throw new HmdpFrameException(message);
        }

        // ===== 第二级检查：本地锁，阻挡同JVM内的并发线程 =====
        // 本地锁是非公平锁，用于快速失败，避免不必要的分布式锁获取
        // 为什么需要本地锁？因为分布式锁获取有网络开销，同JVM内多线程竞争时先拿本地锁快速筛选
        ReentrantLock localLock = localLockCache.getLock(lockName, true);
        boolean localLockResult = localLock.tryLock();
        if (!localLockResult) {
            // 本地锁获取失败，说明有其他线程正在持有锁（可能是同一实例或其他实例持有分布式锁中）
            log.warn("幂等拦截：本地锁获取失败，lockName={}, message={}", lockName, message);
            throw new HmdpFrameException(message);
        }

        try {
            // ===== 第三级检查：分布式锁，跨JVM/跨实例的并发控制 =====
            // 使用公平锁（FairLock），保证先来先服务，避免饥饿
            // 分布式锁是整个幂等机制的核心，确保只有一个实例能执行临界区代码
            ServiceLocker lock = serviceLockFactory.getLock(LockType.Fair);
            boolean result = lock.tryLock(lockName, TimeUnit.SECONDS, 0);

            if (result) {
                try {
                    // ===== 第四级检查：双重Redis检查（兜底） =====
                    // 场景：假设线程A获取了分布式锁，正在执行业务逻辑
                    // 线程B在等待分布式锁（之前没拿到本地锁所以在这等）
                    // 如果线程A执行很快，可能在释放锁后，Redis标记还未过期的窗口期内，线程B获取到了锁
                    // 此时再次检查Redis标记可以防止这种情况
                    flagObject = redissonDataHandle.get(repeatFlagName);
                    if (SUCCESS_FLAG.equals(flagObject)) {
                        log.warn("幂等拦截：双重检查发现重复执行，lockName={}, message={}", lockName, message);
                        throw new HmdpFrameException(message);
                    }

                    // ===== 执行业务逻辑 =====
                    // 到达这里说明：1）Redis标记不存在 2）本地锁拿到了 3）分布式锁拿到了 4）双重检查通过
                    // 可以安全执行核心业务了
                    obj = joinPoint.proceed();

                    // ===== 第五步：写入成功标记到Redis =====
                    // 业务执行成功后，在Redis中写入成功标记
                    // 后续相同消息/请求到达时，第一级检查就会发现标记存在，直接拒绝
                    // durationTime：标记的有效期，过了这个时间后标记自动失效，允许重新处理
                    if (durationTime > 0) {
                        try {
                            redissonDataHandle.set(repeatFlagName, SUCCESS_FLAG, durationTime, TimeUnit.SECONDS);
                            log.debug("幂等标记写入成功，repeatFlagName={}, durationTime={}s", repeatFlagName, durationTime);
                        } catch (Exception e) {
                            // 写入失败不影响主流程，只是失去幂等保护，打印日志即可
                            log.error("幂等标记写入失败（不影响业务），repeatFlagName={}", repeatFlagName, e);
                        }
                    }

                    return obj;

                } finally {
                    // ===== 释放分布式锁 =====
                    // 必须放在finally中，确保无论业务执行成功还是异常都释放锁
                    lock.unlock(lockName);
                }
            } else {
                // 分布式锁获取失败
                log.warn("幂等拦截：分布式锁获取失败，lockName={}, message={}", lockName, message);
                throw new HmdpFrameException(message);
            }
        } finally {
            // ===== 释放本地锁 =====
            // 必须放在finally中，确保无论业务执行成功还是异常都释放锁
            localLock.unlock();
        }
    }
}
