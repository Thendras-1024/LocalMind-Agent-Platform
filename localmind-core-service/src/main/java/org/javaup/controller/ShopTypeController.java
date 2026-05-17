package org.javaup.controller;


import jakarta.annotation.Resource;
import org.javaup.dto.Result;
import org.javaup.entity.ShopType;
import org.javaup.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 商铺类型api
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        return Result.ok(typeList);
    }
}
