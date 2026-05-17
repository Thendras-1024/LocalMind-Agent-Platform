package org.javaup.service;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 令牌
 * @author: 阿星不是程序员
 **/
public interface ISeckillAccessTokenService {
  
    boolean isEnabled();
 
    String issueAccessToken(Long voucherId, Long userId);
    
    boolean validateAndConsume(Long voucherId, Long userId, String token);
}