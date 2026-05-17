package org.javaup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.javaup.entity.Voucher;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 优惠券 Mapper
 * @author: 阿星不是程序员
 **/
public interface VoucherMapper extends BaseMapper<Voucher> {

    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
