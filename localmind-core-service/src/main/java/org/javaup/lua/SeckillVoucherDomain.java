package org.javaup.lua;

import lombok.Data;

/**
 * @program: 智邻生活 Agent 平台
 * @description: lua秒杀返回数据
 * @author: 阿星不是程序员
 **/
@Data
public class SeckillVoucherDomain {

    private Integer code;
    
    private Integer beforeQty;
    
    private Integer deductQty;
    
    private Integer afterQty;

}
