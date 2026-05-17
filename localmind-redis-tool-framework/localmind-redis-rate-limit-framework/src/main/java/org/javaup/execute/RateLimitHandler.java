package org.javaup.execute;
import org.javaup.ratelimit.extension.RateLimitScene;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 限流执行 接口
 * @author: 阿星不是程序员
 **/
public interface RateLimitHandler {
   
    void execute(Long voucherId, Long userId, RateLimitScene scene);
}
