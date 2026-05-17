package org.javaup.kafka.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 秒杀券缓存失效广播消息
 * @author: 阿星不是程序员
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SeckillVoucherInvalidationMessage {
    
    private Long voucherId;
    
    private String reason;
}