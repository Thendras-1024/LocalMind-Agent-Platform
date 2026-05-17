package org.javaup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.javaup.entity.SeckillVoucher;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 秒杀优惠券表，与优惠券是一对一关系 Mapper
 * @author: 阿星不是程序员
 **/
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {
   
    @Update("UPDATE tb_seckill_voucher SET stock = stock + 1,update_time = NOW() WHERE voucher_id = #{voucherId}")
    Integer rollbackStock(@Param("voucherId")Long voucherId);

}
