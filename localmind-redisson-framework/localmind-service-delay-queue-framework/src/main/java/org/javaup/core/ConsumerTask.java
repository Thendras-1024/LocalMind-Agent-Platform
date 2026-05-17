package org.javaup.core;

/**
 * @program: 智邻生活 Agent 平台 
 * @description: 延迟队列 消费者接口
 * @author: 阿星不是程序员
 **/
public interface ConsumerTask {
    
    void execute(String content);
  
    String topic();
}
