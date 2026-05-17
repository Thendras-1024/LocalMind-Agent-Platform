package org.javaup.servicelock.info;

/**
 * @program: 智邻生活 Agent 平台 
 * @description: 处理失败抽象
 * @author: 阿星不是程序员
 **/
public interface LockTimeOutHandler {
    
    /**
     * 处理
     * @param lockName 锁名
     * */
    void handler(String lockName);
}
