package org.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.dto.LoginFormDTO;
import org.javaup.dto.Result;
import org.javaup.entity.User;
import jakarta.servlet.http.HttpSession;


/**
 * @program: 智邻生活 Agent 平台
 * @description: 用户 接口
 * @author: 阿星不是程序员
 **/
public interface IUserService extends IService<User> {

    Result<String> sendCode(String phone, HttpSession session);

    Result<String> login(LoginFormDTO loginForm, HttpSession session);

    Result<Void> sign();

    Result<Integer> signCount();

}
