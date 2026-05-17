package org.javaup.service;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 对账执行 接口
 * @author: 阿星不是程序员
 **/
public interface IReconciliationTaskService {
    
    void reconciliationTaskExecute();

    /**
     * 删除指定券的 Redis 库存键，触发按需重载。
     */
    void delRedisStock(Long voucherId);
}
