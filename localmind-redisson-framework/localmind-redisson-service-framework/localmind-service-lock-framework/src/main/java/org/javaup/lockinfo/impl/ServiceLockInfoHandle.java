package org.javaup.lockinfo.impl;

import org.javaup.lockinfo.AbstractLockInfoHandle;

/**
 * @program: 智邻生活 Agent 平台 
 * @description: 锁信息
 * @author: 阿星不是程序员
 **/
public class ServiceLockInfoHandle extends AbstractLockInfoHandle {

    private static final String LOCK_PREFIX_NAME = "SERVICE_LOCK";
    
    @Override
    protected String getLockPrefixName() {
        return LOCK_PREFIX_NAME;
    }
}
