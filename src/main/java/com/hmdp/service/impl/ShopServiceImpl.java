package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        //缓存穿透
//        queryWithPassThrough(id);
        //互斥锁缓存击穿
//        Shop shop = queryWithMutex(id);
        //逻辑过期实现缓存击穿
//        Shop shop = queryWithLogicExpire(id);

        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    //缓存穿透
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否为空值
        if (shopJson != null) {
            return null;
        }

        Shop shop = getById(id);
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        Integer number = Integer.valueOf(RandomUtil.randomNumbers(1));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + number, TimeUnit.MINUTES);
        return shop;
    }

    //互斥锁实现缓存击穿
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否为空值
        if (shopJson != null) {
            return null;
        }

        //实现缓存重建
        String lockKey = "lock:shop:" + id;
        Shop shop = null;

        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                Thread.sleep(50);
                queryWithMutex(id);
            }

            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            Integer number = Integer.valueOf(RandomUtil.randomNumbers(1));
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + number, TimeUnit.MINUTES);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            unlock(lockKey);
        }
        return shop;
    }

    //逻辑过期实现缓存击穿
    public Shop queryWithLogicExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject shopData = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(shopData, Shop.class);

        LocalDateTime expireTime = redisData.getExpireTime();
        //未过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }

        //已过期
        String lockKey = "lock:shop:" + id;
        boolean lock = tryLock(lockKey);
        if (lock) {
            //开启独立线程,实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    unlock(lockKey);
                }
            });
        }

        //返回旧数据
        return shop;
    }


    public void saveShop2Redis(Long id, Long expireSeconds) {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        redisData.setData(shop);

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result updateByMe(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        String key = CACHE_SHOP_KEY + shop.getId();
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(key);

        return Result.ok();
    }

    private boolean tryLock(String key) {
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
