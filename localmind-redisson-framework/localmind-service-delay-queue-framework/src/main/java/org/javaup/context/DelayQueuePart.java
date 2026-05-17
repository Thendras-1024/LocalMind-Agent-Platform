package org.javaup.context;

import org.javaup.core.ConsumerTask;
import lombok.Data;

/**
 * @program: 智邻生活 Agent 平台 
 * @description: 消息主题
 * @author: 阿星不是程序员
 **/
@Data
public class DelayQueuePart {
    
    private final DelayQueueBasePart delayQueueBasePart;
 
    private final ConsumerTask consumerTask;
    
    public DelayQueuePart(DelayQueueBasePart delayQueueBasePart, ConsumerTask consumerTask){
        this.delayQueueBasePart = delayQueueBasePart;
        this.consumerTask = consumerTask;
    }
}
