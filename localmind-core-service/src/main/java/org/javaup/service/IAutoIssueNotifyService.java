package org.javaup.service;


/**
 * @program: 智邻生活 Agent 平台
 * @description: 自动发券成功后的用户通知服务接口
 * @author: 阿星不是程序员
 **/
public interface IAutoIssueNotifyService {
    
    void sendAutoIssueNotify(Long voucherId, Long userId, Long orderId);
}