package org.javaup.service;

import org.javaup.entity.RollbackFailureLog;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 回滚失败通知服务：用于发送短信/邮件告警（可插拔实现）。
 * @author: 阿星不是程序员
 **/
public interface IRollbackAlertService {

    void sendRollbackAlert(RollbackFailureLog log);
}