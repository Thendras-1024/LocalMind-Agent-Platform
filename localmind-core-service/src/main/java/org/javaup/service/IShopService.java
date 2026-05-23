package org.javaup.service;

import org.javaup.dto.Result;
import org.javaup.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 商铺 接口
 * @author: 阿星不是程序员
 **/
public interface IShopService extends IService<Shop> {

    Result saveShop(Shop shop);
    
    Result queryById(Long id);

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);

    Result queryNearbyShops(Integer typeId, Integer current, Double x, Double y, Integer radius);
}
