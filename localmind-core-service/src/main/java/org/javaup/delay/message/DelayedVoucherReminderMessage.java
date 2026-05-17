package org.javaup.delay.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 开抢提醒消息DTO
 * @author: 阿星不是程序员
 **/
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DelayedVoucherReminderMessage {
    
    private Long voucherId;
    
    private LocalDateTime beginTime;
}