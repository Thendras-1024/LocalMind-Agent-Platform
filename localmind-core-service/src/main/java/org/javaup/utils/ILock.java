package org.javaup.utils;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 锁-黑马点评普通版本使用
 * @author: 阿星不是程序员
 **/
public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间，过期后自动释放
     * @return true代表获取锁成功; false代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
