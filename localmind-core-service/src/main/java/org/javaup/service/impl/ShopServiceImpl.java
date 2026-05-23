package org.javaup.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.config.NearbyLocationProperties;
import org.javaup.core.RedisKeyManage;
import org.javaup.dto.Result;
import org.javaup.entity.Shop;
import org.javaup.handler.BloomFilterHandlerFactory;
import org.javaup.mapper.ShopMapper;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IShopService;
import org.javaup.servicelock.LockType;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.javaup.util.ServiceLockTool;
import org.javaup.utils.CacheClient;
import org.javaup.utils.SystemConstants;
import org.redisson.api.RLock;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.Constant.BLOOM_FILTER_HANDLER_SHOP;
import static org.javaup.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.javaup.utils.RedisConstants.CACHE_SHOP_TTL;
import static org.javaup.utils.RedisConstants.LOCK_SHOP_KEY;
import static org.javaup.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 商铺服务实现类
 *
 * <p>核心功能：商铺信息查询、缓存管理、地理位置检索
 *
 * <p>缓存策略：采用"布隆过滤器 + 双检分布式锁 + 空值缓存"方案
 * <ul>
 *   <li>布隆过滤器：判断key是否一定不存在，解决缓存穿透</li>
 *   <li>双检锁：防止缓存击穿，只有一个线程查DB</li>
 *   <li>空值缓存：防止不存在key的穿透</li>
 * </ul>
 *
 * <p>版本演进：
 * <ul>
 *   <li>V1: 穿透式旁路缓存（基本款，无法防击穿）</li>
 *   <li>V2: 互斥锁（解决击穿，但所有请求要等锁）</li>
 *   <li>V3: 逻辑过期（解决击穿，但返回过期数据）</li>
 *   <li>V4: 布隆+双检锁+空值（完整方案，生产使用）</li>
 * </ul>
 *
 * @author 阿星不是程序员
 **/
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    
    @Resource
    private ServiceLockTool serviceLockTool;
    
    @Resource
    private RedisCache redisCache;
    
    @Resource
    private BloomFilterHandlerFactory bloomFilterHandlerFactory;
    
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Resource
    private NearbyLocationProperties nearbyLocationProperties;
    
    
    /**
     * 保存商铺
     * <p>流程：写入数据库 → 写入布隆过滤器 → 返回ID
     *
     * @param shop 商铺信息
     * @return 商铺ID
     */
    @Override
    public Result<Long> saveShop(final Shop shop) {
        // 写入数据库
        shop.setId(snowflakeIdGenerator.nextId());
        save(shop);
        refreshShopGeo(shop);
        // 写入布隆过滤器（商铺业务）
        bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SHOP).add(String.valueOf(shop.getId()));
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    /**
     * 查询商铺详情（Plus版完整方案）
     * <p>查询顺序：Redis缓存 → 布隆过滤器 → 空值缓存 → 双检锁 → DB
     * <p>解决的问题：缓存穿透（布隆+空值）、缓存击穿（双检锁）
     *
     * @param id 商铺ID
     * @return 商铺信息
     */
    @Override
    public Result queryById(Long id) {
        //Shop shop = queryByIdV1(id);  // V1: 穿透式旁路缓存
        //shop = queryByIdV2(id);        // V2: 互斥锁解决击穿
        //shop = queryByIdV3(id);        // V3: 逻辑过期解决击穿

        // 🚀 V4: 完美的方案！使用布隆过滤器 + 双重检测锁 + 空值配置，彻底解决缓存穿透和击穿
        Shop shop = queryByIdV4(id);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 返回商铺信息
        return Result.ok(shop);
    }

    /**
     * V1版本：穿透式旁路缓存（基本款）
     * <p>流程：查Redis → 没有查DB → 写回Redis
     * <p>问题：无法解决缓存击穿
     */
    public Shop queryByIdV1(Long id){
        return cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }

    /**
     * V2版本：互斥锁解决缓存击穿
     * <p>流程：查Redis → 没有则获取互斥锁 → 查DB → 写回Redis → 释放锁
     * <p>问题：所有请求都要等待锁，性能差
     */
    public Shop queryByIdV2(Long id){
        return cacheClient
                .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }

    /**
     * V3版本：逻辑过期解决缓存击穿
     * <p>流程：查Redis → 已过期则获取锁 → 异步线程查DB重建 → 直接返回旧数据
     * <p>问题：返回的是过期数据，不适合严格要求一致性的场景
     */
    public Shop queryByIdV3(Long id){
        return cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
    }
    
    public Shop queryByIdV4(Long id){
        // ===== 第1层：查Redis缓存 =====
        Shop shop =
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY, id), Shop.class);
        // 命中直接返回
        if (Objects.nonNull(shop)) {
            return shop;
        }
        log.info("查询商铺 从Redis缓存没有查询到 商铺id : {}",id);

        // ===== 第2层：布隆过滤器检查（防缓存穿透） =====
        // 布隆过滤器判断key一定不存在，则直接返回，不查DB
        if (!bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SHOP).contains(String.valueOf(id))) {
            log.info("查询商铺 布隆过滤器判断不存在 商铺id : {}",id);
            throw new RuntimeException("查询商铺不存在");
        }

        // ===== 第3层：空值缓存检查（防缓存穿透） =====
        // 之前查询不存在的key会被缓存为空值，这里检查避免重复查DB
        Boolean existResult = redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY_NULL, id));
        if (existResult){
            throw new RuntimeException("查询商铺不存在");
        }

        // ===== 第4层：双检分布式锁（防缓存击穿） =====
        // 获取分布式锁，只有一个线程能进入重建逻辑
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, LOCK_SHOP_KEY, new String[]{String.valueOf(id)});
        lock.lock();
        try {
            // 获取锁后再次检查空值缓存（其他线程可能已经写入）
            existResult = redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY_NULL, id));
            if (existResult){
                throw new RuntimeException("查询商铺不存在");
            }

            // 双重检查Redis缓存（其他线程可能已经写入）
            shop = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY, id), Shop.class);
            if (Objects.nonNull(shop)) {
                return shop;
            }

            // ===== 第5层：查数据库 =====
            shop = getById(id);
            if (Objects.isNull(shop)) {
                // 数据库也不存在，写入空值缓存防止穿透（短TTL）
                redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY_NULL, id),
                        "这是一个空值",
                        CACHE_SHOP_TTL,
                        TimeUnit.MINUTES);
                throw new RuntimeException("查询商铺不存在");
            }

            // ===== 第6层：写入缓存 =====
            // 写入Redis缓存，设置TTL
            redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY, id),shop,
                    CACHE_SHOP_TTL,
                    TimeUnit.MINUTES);
            return shop;
        }finally {
            // ===== 释放分布式锁 =====
            lock.unlock();
        }
    }

    /**
     * 更新商铺信息
     * <p>流程：更新数据库 → 删除缓存（不是更新缓存，因为Cache-Aside是删除而不是更新）
     *
     * @param shop 商铺信息
     * @return 操作结果
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        Shop oldShop = getById(id);
        // 1.更新数据库
        updateById(shop);
        removeShopGeo(oldShop);
        refreshShopGeo(getById(id));
        // 2.删除缓存（Cache-Aside模式：更新DB后删除缓存，下次查询会加载最新数据）
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    private void refreshShopGeo(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getTypeId() == null || shop.getX() == null || shop.getY() == null) {
            return;
        }
        String key = SHOP_GEO_KEY + shop.getTypeId();
        stringRedisTemplate.opsForGeo().add(
                key,
                new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY()))
        );
    }

    private void removeShopGeo(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getTypeId() == null) {
            return;
        }
        stringRedisTemplate.opsForGeo().remove(SHOP_GEO_KEY + shop.getTypeId(), shop.getId().toString());
    }

    /**
     * 根据类型查询商铺列表（支持按距离排序）
     * <p>两种模式：
     * <ul>
     *   <li>无坐标查询：直接查数据库分页</li>
     *   <li>有坐标查询：Redis GEO查询按距离排序后返回</li>
     * </ul>
     *
     * @param typeId 商铺类型ID
     * @param current 当前页
     * @param x 经度（可选）
     * @param y 纬度（可选）
     * @return 商铺列表
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        return queryShopByGeo(typeId, current, x, y, 5000);
    }

    @Override
    public Result queryNearbyShops(Integer typeId, Integer current, Double x, Double y, Integer radius) {
        return queryShopByGeo(typeId, current, x, y, normalizeRadius(radius));
    }

    private Result queryShopByGeo(Integer typeId, Integer current, Double x, Double y, int radiusMeters) {
        if (!Boolean.TRUE.equals(nearbyLocationProperties.getRealCoordinateEnabled())) {
            x = nearbyLocationProperties.getMockX();
            y = nearbyLocationProperties.getMockY();
        }

        // ===== 无坐标模式：直接查数据库分页 =====
        if (x == null || y == null) {
            // 按类型分页查询数据库
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回商铺列表
            return Result.ok(page.getRecords());
        }

        // ===== 有坐标模式：Redis GEO查询 =====
        // 计算分页边界
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 1. 查询Redis GEO：使用 GEORADIUS 兼容 Redis 5，获取指定半径内商铺并按距离排序
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(
                        key,
                        new Circle(new Point(x, y), new Distance(radiusMeters / 1000.0, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(end)
                );

        // 2. 解析结果
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        // 没有更多数据了
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        // 3. 截取分页数据
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 获取商铺ID
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        // 4. 根据ID批量查询商铺详情
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        // 5. 设置距离信息
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        // 6. 返回结果
        return Result.ok(shops);
    }

    private int normalizeRadius(Integer radius) {
        if (radius == null || radius <= 0) {
            return 5000;
        }
        if (radius <= 10) {
            return radius * 1000;
        }
        return Math.min(radius, 10000);
    }
}
