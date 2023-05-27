package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * @Description 用于拦截请求，刷新token时效
 * @author ehyzil
 * @create 2023-06-2023/6/10-16:07
 */
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        log.info("获取用户请求路径:{}\t",request.getRequestURL());

        //获取请求头中的token
        String token = request.getHeader("authorization");
        //如果token是空，则未登录，拦截
        if (StrUtil.isBlank(token)) {
           return  true;
        }

        String key = LOGIN_USER_KEY + token;

        //基于token获取Redis中的用户数据
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        //判断用户是否存在，不存在，放行
        if (userMap.isEmpty()) {
            return true;
        }

        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //将用户信息保存到ThreadLocal
        UserHolder.saveUser(user);

        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);


        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
       UserHolder.removeUser();
    }
}
