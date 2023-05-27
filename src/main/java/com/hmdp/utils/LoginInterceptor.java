package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 判断拦截器中的user对象是否存在即可
 * @author ehyzil
 * @create 2023-06-2023/6/10-11:39
 */
@Slf4j
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 判断用户是否存在，不存在，则拦截
        if (UserHolder.getUser() == null) {
            log.info("获取用户请求路径:{},用户不存在\t",request.getRequestURL());
            response.setStatus(401);
            return false;
        }
        //存在则放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
