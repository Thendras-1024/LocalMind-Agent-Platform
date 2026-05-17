package org.javaup.core;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;

/**
 * @program: 智邻生活 Agent 平台 
 * @description: 延迟队列 阻塞队列
 * @author: 阿星不是程序员
 **/
@Slf4j
public class DelayBaseQueue {
    
    protected final RedissonClient redissonClient;
    protected final RBlockingQueue<String> blockingQueue;
    
    
    public DelayBaseQueue(RedissonClient redissonClient,String relTopic){
        this.redissonClient = redissonClient;
        this.blockingQueue = redissonClient.getBlockingQueue(relTopic);
    }
}
