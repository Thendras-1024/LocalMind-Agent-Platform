package org.javaup.service.impl;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.cache.SeckillVoucherLocalCache;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.SeckillVoucher;
import org.javaup.entity.Voucher;
import org.javaup.handler.BloomFilterHandlerFactory;
import org.javaup.mapper.SeckillVoucherMapper;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IVoucherService;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.annotion.ServiceLock;
import org.javaup.util.ServiceLockTool;
import org.redisson.api.RLock;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.Constant.BLOOM_FILTER_HANDLER_VOUCHER;
import static org.javaup.constant.DistributedLockConstants.UPDATE_SECKILL_VOUCHER_LOCK;
import static org.javaup.constant.DistributedLockConstants.UPDATE_SECKILL_VOUCHER_STOCK_LOCK;
import static org.javaup.utils.RedisConstants.CACHE_NULL_TTL;
import static org.javaup.utils.RedisConstants.LOCK_SECKILL_VOUCHER_KEY;
import static org.javaup.utils.RedisConstants.LOCK_SECKILL_VOUCHER_STOCK_KEY;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 秒杀优惠券 服务实现类
 *              核心功能：多级缓存查询、库存加载、缓存防穿透、分布式锁
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {
    
    @Resource
    private ServiceLockTool serviceLockTool;
    
    @Resource
    private RedisCache redisCache;
    
    @Resource
    private BloomFilterHandlerFactory bloomFilterHandlerFactory;

    @Resource
    private SeckillVoucherLocalCache seckillVoucherLocalCache;
    
    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;
    
    @Resource
    private IVoucherService voucherService;
    
    /**
     * 查询秒杀券详情 - 多级缓存查询
     *
     * 查询顺序：Caffeine本地缓存 → Redis分布式缓存 → 布隆过滤器 → 数据库
     * 缓存穿透防护：布隆过滤器判断不存在时直接返回，不查数据库
     * 缓存击穿防护：分布式锁保证只有一个线程查数据库
     *
     * @param voucherId 秒杀券ID
     * @return 秒杀券完整信息
     */
    @Override
    @ServiceLock(lockType= LockType.Read, name = UPDATE_SECKILL_VOUCHER_LOCK, keys = {"#voucherId"})
    public SeckillVoucherFullModel queryByVoucherId(Long voucherId) {
        // 构建Redis缓存Key：seckill:voucher:{voucherId}
        RedisKeyBuild seckillVoucherRedisKey =
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY, voucherId);
        // 构建空值缓存Key：用于防止缓存穿透
        RedisKeyBuild seckillVoucherNullRedisKey =
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_NULL_TAG_KEY, voucherId);

        // ===== 第1层：Caffeine本地缓存（进程内，零网络开销） =====
        SeckillVoucherFullModel localCacheHit = seckillVoucherLocalCache.get(seckillVoucherRedisKey.getRelKey());
        if (Objects.nonNull(localCacheHit)) {
            return localCacheHit;  // ← 本地缓存命中，直接返回
        }

        // ===== 第2层：Redis分布式缓存 =====
        SeckillVoucherFullModel seckillVoucherFullModel =
                redisCache.get(seckillVoucherRedisKey, SeckillVoucherFullModel.class);
        if (Objects.nonNull(seckillVoucherFullModel)) {
            // ← Redis缓存命中，写入本地缓存后返回
            seckillVoucherLocalCache.put(seckillVoucherRedisKey.getRelKey(), seckillVoucherFullModel);
            return seckillVoucherFullModel;
        }

        // Redis缓存未命中，记录日志
        log.info("查询秒杀优惠券 从Redis缓存没有查询到 秒杀优惠券的优惠券id : {}", voucherId);

        // ===== 第3层：布隆过滤器（防缓存穿透） =====
        // 布隆过滤器判断不存在，说明这个voucherId一定是无效的，直接抛异常
        if (!bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_VOUCHER).contains(String.valueOf(voucherId))) {
            log.info("查询秒杀优惠券 布隆过滤器判断不存在 秒杀优惠券id : {}", voucherId);
            throw new RuntimeException("查询秒杀优惠券不存在");
        }

        // 检查空值缓存（曾经查询过但结果为空的记录）
        Boolean existResult = redisCache.hasKey(seckillVoucherNullRedisKey);
        if (existResult) {
            throw new RuntimeException("查询秒杀优惠券不存在");
        }

        // ===== 第4层：分布式锁 + 数据库查询（防缓存击穿） =====
        // 获取分布式锁，确保只有一个线程去查数据库
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, LOCK_SECKILL_VOUCHER_KEY, new String[]{String.valueOf(voucherId)});
        lock.lock();
        try {
            // 双重检查：获取锁后再次查询Redis，可能其他线程已经写入
            seckillVoucherFullModel = redisCache.get(seckillVoucherRedisKey, SeckillVoucherFullModel.class);
            if (Objects.nonNull(seckillVoucherFullModel)) {
                seckillVoucherLocalCache.put(seckillVoucherRedisKey.getRelKey(), seckillVoucherFullModel);
                return seckillVoucherFullModel;
            }

            // 再次检查空值缓存
            existResult = redisCache.hasKey(seckillVoucherNullRedisKey);
            if (existResult) {
                throw new RuntimeException("查询优惠券不存在");
            }

            // 查询数据库
            SeckillVoucher seckillVoucher = lambdaQuery().eq(SeckillVoucher::getVoucherId, voucherId).one();

            // 数据库也无此记录，写入空值缓存（2分钟TTL）防止缓存穿透
            if (Objects.isNull(seckillVoucher)) {
                redisCache.set(seckillVoucherNullRedisKey,
                        "这是一个空值",
                        CACHE_NULL_TTL,  // 空值缓存2分钟
                        TimeUnit.MINUTES);
                throw new RuntimeException("查询秒杀优惠券不存在");
            }

            // 计算TTL：秒杀结束时间 - 当前时间（确保秒杀结束后缓存自然失效）
            long ttlSeconds = Math.max(
                    LocalDateTimeUtil.between(LocalDateTimeUtil.now(), seckillVoucher.getEndTime()).getSeconds(),
                    1L
            );

            // 查询关联的Voucher表获取更多信息（店铺ID、状态）
            Voucher voucher = voucherService.lambdaQuery().eq(Voucher::getId, voucherId).one();

            // 构建完整的秒杀券信息
            seckillVoucherFullModel = new SeckillVoucherFullModel();
            BeanUtils.copyProperties(seckillVoucher, seckillVoucherFullModel);  // 复制属性
            seckillVoucherFullModel.setShopId(voucher.getShopId());         // 设置店铺ID
            seckillVoucherFullModel.setStatus(voucher.getStatus());        // 设置状态
            seckillVoucherFullModel.setStock(null);                        // 库存不缓存（单独管理）

            // 写入Redis缓存
            redisCache.set(
                    seckillVoucherRedisKey,
                    seckillVoucherFullModel,
                    ttlSeconds,
                    TimeUnit.SECONDS
            );

            // 写入本地缓存
            seckillVoucherLocalCache.put(seckillVoucherRedisKey.getRelKey(), seckillVoucherFullModel);

            return seckillVoucherFullModel;
        } finally {
            lock.unlock();  // 释放分布式锁
        }
    }
    
    /**
     * 加载秒杀券库存到Redis
     *
     * 库存是高频访问且需要精确的数据，不使用本地缓存，只用Redis分布式缓存
     * 防穿透：布隆过滤器判断不存在时直接返回
     * 防击穿：分布式锁保证只有一个线程查数据库
     *
     * @param voucherId 秒杀券ID
     */
    @Override
    @ServiceLock(lockType= LockType.Read, name = UPDATE_SECKILL_VOUCHER_STOCK_LOCK, keys = {"#voucherId"})
    public void loadVoucherStock(Long voucherId) {
        // ===== 第1层：布隆过滤器（防缓存穿透） =====
        if (!bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_VOUCHER).contains(String.valueOf(voucherId))) {
            log.info("加载库存 布隆过滤器判断不存在 秒杀优惠券id : {}", voucherId);
            throw new RuntimeException("查询秒杀优惠券不存在");
        }

        // ===== 第2层：检查Redis库存是否已存在 =====
        String stock =
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId), String.class);
        if (Objects.nonNull(stock)) {
            return;  // ← 库存已存在，直接返回
        }

        // ===== 第3层：分布式锁 + 数据库查询（防缓存击穿） =====
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, LOCK_SECKILL_VOUCHER_STOCK_KEY,
                new String[]{String.valueOf(voucherId)});
        lock.lock();
        try {
            // 双重检查：获取锁后再次检查Redis
            stock = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId), String.class);
            if (Objects.nonNull(stock)) {
                return;
            }

            // 查询数据库中的库存
            SeckillVoucher seckillVoucher = lambdaQuery().eq(SeckillVoucher::getVoucherId, voucherId).one();

            if (Objects.nonNull(seckillVoucher)) {
                // 计算TTL：秒杀结束时间 - 当前时间
                long ttlSeconds = Math.max(
                        LocalDateTimeUtil.between(LocalDateTimeUtil.now(), seckillVoucher.getEndTime()).getSeconds(),
                        1L
                );

                // 写入Redis库存缓存
                redisCache.set(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId),
                        String.valueOf(seckillVoucher.getStock()),  // 库存转为字符串存储
                        ttlSeconds,
                        TimeUnit.SECONDS
                );
            }
        } finally {
            lock.unlock();  // 释放分布式锁
        }
    }
    
    /**
     * 回滚秒杀券库存（数据库层面）
     *
     * 用于取消订单时恢复数据库中的库存
     * 实际SQL：UPDATE tb_seckill_voucher SET stock = stock + 1 WHERE voucher_id = ?
     *
     * @param voucherId 秒杀券ID
     * @return true=回滚成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rollbackStock(final Long voucherId) {
        // 调用Mapper执行库存回滚SQL
        return seckillVoucherMapper.rollbackStock(voucherId) > 0;
    }
}
