package org.javaup.model;

import lombok.Data;

/**
 * @program: 智邻生活 Agent 平台
 * @description: redis 中的记录日志信息
 * @author: 阿星不是程序员
 **/
@Data
public class RedisTraceLogModel {

    private String logType;
    
    private Long ts;
    
    private String orderId;
    
    private String traceId;
    
    private String userId;
    
    private String voucherId;
    
    private Integer beforeQty;
    
    private Integer changeQty;
    
    private Integer afterQty;
}
