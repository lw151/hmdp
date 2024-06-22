package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//
//        Long userId = UserHolder.getUser().getId();
//
//        //1.执行lua脚本
//        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString());
//
//        //2.判断结果是否为0
//        int r = result.intValue();
//        if (r != 0) {
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        //TODO 保存阻塞队列
//
//        return Result.ok(0);
//
//    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);

        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }

        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }

        //4.判读库存是否充足
        Integer stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();

        //创建锁对象
        //      SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("order:" + userId);

        boolean isLock = lock.tryLock();
        if (!isLock) {
            return Result.fail("一个人只允许下一单");
        } else {
            try {
                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
                return proxy.createVoucherOrder(voucherId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                lock.unlock();
            }
        }


    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        SeckillVoucher voucher;
        //一人一单
        Long userId = UserHolder.getUser().getId();
        voucher = iSeckillVoucherService.getById(voucherId);

        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("该用户已经购买过了");
        }

        //5.扣减库存
        boolean success = iSeckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucher.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        //7.返回订单ID
        return Result.ok(orderId);
    }


}

