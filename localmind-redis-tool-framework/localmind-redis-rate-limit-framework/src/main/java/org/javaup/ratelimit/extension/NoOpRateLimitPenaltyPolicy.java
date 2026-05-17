package org.javaup.ratelimit.extension;

import org.javaup.enums.BaseCode;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 默认空实现
 * @author: 阿星不是程序员
 **/
public class NoOpRateLimitPenaltyPolicy implements RateLimitPenaltyPolicy {
    @Override
    public void apply(RateLimitContext ctx, BaseCode reason) {
        // no-op
    }
}