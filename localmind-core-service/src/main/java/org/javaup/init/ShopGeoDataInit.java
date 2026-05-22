package org.javaup.init;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.entity.Shop;
import org.javaup.service.IShopService;
import org.springframework.core.annotation.Order;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.javaup.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Order(2)
@Component
public class ShopGeoDataInit {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void init() {
        log.info("==========初始化商铺 GEO 索引==========");
        List<Shop> shopList = shopService.list();
        Map<Long, List<Shop>> shopMap = shopList.stream()
                .filter(shop -> shop.getTypeId() != null && shop.getX() != null && shop.getY() != null)
                .collect(Collectors.groupingBy(Shop::getTypeId));

        shopMap.forEach((typeId, shops) -> {
            String key = SHOP_GEO_KEY + typeId;
            stringRedisTemplate.delete(key);
            shops.stream()
                    .filter(shop -> Objects.nonNull(shop.getId()))
                    .forEach(shop -> stringRedisTemplate.opsForGeo().add(
                            key,
                            new RedisGeoCommands.GeoLocation<>(
                                    shop.getId().toString(),
                                    new Point(shop.getX(), shop.getY())
                            )
                    ));
        });
        log.info("==========商铺 GEO 索引初始化完成，共 {} 条==========", shopList.size());
    }
}
