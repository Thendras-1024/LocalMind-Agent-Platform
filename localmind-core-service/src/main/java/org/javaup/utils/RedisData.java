package org.javaup.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @program: 智邻生活 Agent 平台
 * @description: redis数据
 * @author: 阿星不是程序员
 **/
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
