package org.javaup.servicelock;

/**
 * @program: 智邻生活 Agent 平台 
 * @description: 分布式锁 锁类型
 * @author: 阿星不是程序员
 **/
public enum LockType {
    /**
     * 锁类型
     */
    Reentrant,
    
    Fair,
   
    Read,
    
    Write;

    LockType() {
    }

}
