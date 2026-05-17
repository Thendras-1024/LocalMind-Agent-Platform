package org.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.dto.GetVoucherOrderRouterDto;
import org.javaup.entity.VoucherOrderRouter;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 优惠券订单路由 接口
 * @author: 阿星不是程序员
 **/
public interface IVoucherOrderRouterService extends IService<VoucherOrderRouter> {
    
    Long get(GetVoucherOrderRouterDto getVoucherOrderRouterDto);
}
