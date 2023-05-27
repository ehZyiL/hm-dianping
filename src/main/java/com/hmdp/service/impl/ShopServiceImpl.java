package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    //这里需要声明一个线程池，因为下面我们需要新建一个现成来完成重构缓存
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopById(Long id) {
        Shop shop = queryWithPassThrough(id);
//        Shop shop = queryWithMutex(id);
//        Shop shop =queryWithLogicalExpire(id);
        if (shop == null) return Result.fail("店铺不存在！！");
        return Result.ok();
    }

    /**
     * 解锁缓存穿透
     *
     * @param id
     * @return
     */
    private Shop queryWithPassThrough(Long id) {
        Shop shop = null;
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //如果不为空（查询到了），则转为Shop类型直接返回,不会拦截空字符串
        if (StrUtil.isNotBlank(shopJson)) {
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //如果查到的是空字符串，则说明是我们缓存的空数据
        if (shopJson != null) return null;
        //否则去数据库中查
        shop = getById(id);
        //查不到，就写入空字符串到redis
        if (shop == null) {
            log.info("id:{}的店铺不存在，写入空字符串", id);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, " ", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //查到了则转为json字符串
        shopJson = JSONUtil.toJsonStr(shop);
        //并存入redis
        stringRedisTemplate.opsForValue().set(key, shopJson);
        //最终把查询到的商户信息返回给前端
        return shop;
    }

    /**
     * 互斥锁解决缓存击穿
     *
     * @param id
     * @return
     */
    private Shop queryWithMutex(Long id) {
        Shop shop = null;
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //如果不为空（查询到了），则转为Shop类型直接返回,不会拦截空字符串
        if (StrUtil.isNotBlank(shopJson)) {
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //如果查到的是空字符串，则说明是我们缓存的空数据
        if (shopJson != null) return null;

        String lockKey = LOCK_SHOP_KEY + id;
        try {
            //获取锁
            boolean flag = tryLock(lockKey);
            //未获取到，休眠一段时间在获取
            if (!flag) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //获取到锁
            //否则去数据库中查
            shop = getById(id);
            //查不到，就写入空字符串到redis
            if (shop == null) {
                log.info("id:{}的店铺不存在，写入空字符串", id);
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, " ", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //查到了则转为json字符串
            shopJson = JSONUtil.toJsonStr(shop);
            //并存入redis
            stringRedisTemplate.opsForValue().set(key, shopJson);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            //最后释放锁
            unlock(lockKey);
        }


        //最终把查询到的商户信息返回给前端
        return shop;
    }

    /**
     * 获取锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //避免返回值为null，我们这里使用了BooleanUtil工具类
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 利用逻辑过期解决缓存击穿问题
     *
     * @param id
     * @return
     */
    private Shop queryWithLogicalExpire(Long id) {
        Shop shop = null;
        String key = CACHE_SHOP_KEY + id;
        String redisDataJson = stringRedisTemplate.opsForValue().get(key);
        //如果不为空（查询到了），则转为Shop类型直接返回,不会拦截空字符串
        if (StrUtil.isBlank(redisDataJson)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        JSONObject shopJson = (JSONObject) redisData.getData();
        shop = JSONUtil.toBean(shopJson, Shop.class);

        LocalDateTime expireTime = redisData.getExpireTime();
        if (LocalDateTime.now().isBefore(expireTime)) {
            return shop;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        //获取锁
        boolean flag = tryLock(lockKey);
        //获取到，休眠一段时间在获取
        if (flag) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {

                try {
                    this.saveShop2Redis(id, LOCK_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //最后释放锁
                    unlock(lockKey);
                }
            });

            return queryWithMutex(id);
        }


        //最终把查询到的商户信息返回给前端
        return shop;
    }

    @Override
    public Result update(Shop shop) {
        //首先先判一下空
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空！！");
        }
        //先修改数据库
        updateById(shop);
        //再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {

        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，根据类型分页查询
            Page<Shop> page = query().eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().
                search(key, GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        // 4.解析出id
        if (results == null) return Result.ok(Collections.emptyList());
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        if (list.size() < end) return Result.ok(Collections.emptyList());



        // 4.1.截取 from ~ end的部分
        //存放要查询的id
        List<Long> ids = new ArrayList<>(list.size());
        //存放店铺id和距离
        Map<String, Distance> distanceMap = new HashMap<>(list.size());

        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }

    public void saveShop2Redis(Long id, Long expirSeconds) {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expirSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

}
