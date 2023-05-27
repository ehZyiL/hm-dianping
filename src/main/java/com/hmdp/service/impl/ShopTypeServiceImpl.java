package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        String key = CACHE_SHOP_TYPE_KEY;
        List<ShopType> shopTypeList = null;
        //先从Redis中查，这里的常量值是固定的前缀 + 店铺id
        List<String> shopTypesJson = stringRedisTemplate.opsForList().range(key, 0, -1);
        //如果不为空（查询到了），则转为Shop类型直接返回
        if (!shopTypesJson.isEmpty()) {
            shopTypeList = new ArrayList<>();
            for (String shopTypeJson : shopTypesJson) {
                ShopType shopType = JSONUtil.toBean(shopTypeJson, ShopType.class);
                shopTypeList.add(shopType);
            }
            return Result.ok(shopTypeList);
        }
        //否则去数据库中查
        shopTypeList = this.query().orderByDesc("sort").list();

        if (shopTypeList.isEmpty()) {
            return Result.fail("店铺信息异常");
        }
        //查到了则转为json字符串
        for (ShopType shopType : shopTypeList) {
            String shopTypeJson = JSONUtil.toJsonStr(shopType);
            shopTypesJson.add(shopTypeJson);
        }
        //并存入redis
        stringRedisTemplate.opsForList().leftPushAll(key, shopTypesJson);
        //最终把查询到的商户信息返回给前端
        return Result.ok(shopTypeList);
    }
}
