package org.javaup.dto;

import lombok.Data;
/**
 * @program: 智邻生活 Agent 平台
 * @description: 用户-入参
 * @author: 阿星不是程序员
 **/
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}
