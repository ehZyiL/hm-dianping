package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {
    /**
     * 发送验证码
     *
     * @return
     */
    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result logout(String token);

    /**
     * 根据id查询用户
     * @param userId
     * @return
     */
    Result queryUserById(Long userId);

    /**
     * 用户签到
     * @return
     */
    Result sign();

    /**
     * 统计签到
     * @return
     */
    Result signCount();
}
