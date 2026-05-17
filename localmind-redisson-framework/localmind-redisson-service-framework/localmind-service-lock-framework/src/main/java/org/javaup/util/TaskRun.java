package org.javaup.util;

/**
 * @program: 智邻生活 Agent 平台 
 * @description: 分布式锁 方法类型执行 无返回值的业务
 * @author: 阿星不是程序员
 **/
@FunctionalInterface
public interface TaskRun {
    
    /**
     * 执行任务
     * */
    void run();
}
