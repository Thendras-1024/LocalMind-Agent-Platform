package org.javaup.dto;

import lombok.Data;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 登录-入参
 * @author: 阿星不是程序员
 **/
@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}
