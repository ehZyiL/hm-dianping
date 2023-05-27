package com.hmdp.config;

/**
 * @author ehyzil
 * @Description
 * @create 2023-06-2023/6/17-11:18
 */
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://192.168.154.141:6379")
                .setPassword("111");
        return Redisson.create(config);
    }
}