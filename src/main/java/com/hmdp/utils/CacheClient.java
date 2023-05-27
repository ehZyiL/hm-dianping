package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;

/**
 * @author ehyzil
 * @Description
 * @create 2023-06-2023/6/15-19:45
 */
public class CacheClient {

    //这里需要声明一个线程池，因为下面我们需要新建一个现成来完成重构缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 将任意Java对象序列化为JSON，并存储到String类型的Key中，并可以设置TTL过期时间
     *
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 将任意Java对象序列化为JSON，并存储在String类型的Key中，并可以设置逻辑过期时间，用于处理缓存击穿问题
     *
     * @param key
     * @param value
     * @param time
     * @param timeUnit
     */
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        //由于需要设置逻辑过期时间，所以我们需要用到RedisData
        RedisData redisData = new RedisData();
        //redisData的data就是传进来的value对象
        redisData.setData(value);
        //逻辑过期时间就是当前时间加上传进来的参数时间，用TimeUnit可以将时间转为秒，随后与当前时间相加
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        //由于是逻辑过期，所以这里不需要设置过期时间，只存一下key和value就好了，同时注意value是ridisData类型
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 将任意Java对象序列化为JSON，并存储在String类型的Key中，并可以设置逻辑过期时间，用于处理缓存击穿问题
     *
     * @param keyPreFix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param timeUnit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPreFix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {

        R r = null;

        String key = keyPreFix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        //如果不为空（查询到了），则转为Shop类型直接返回,不会拦截空字符串
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        //如果查到的是空字符串，则说明是我们缓存的空数据
        if (json != null) return null;

        //否则去数据库中查
        r = dbFallback.apply(id);
        //查不到，就写入空字符串到redis
        if (r == null) {
            stringRedisTemplate.opsForValue().set(keyPreFix + id, " ", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //查到了则转为json字符串
        json = JSONUtil.toJsonStr(r);
        //并存入redis
        stringRedisTemplate.opsForValue().set(key, json, time, timeUnit);
        //最终把查询到的商户信息返回给前端
        return r;
    }

}
