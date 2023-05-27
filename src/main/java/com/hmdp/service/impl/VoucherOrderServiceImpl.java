package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    //脚本配置
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建阻塞队列
//    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    String queueName = "stream.orders";
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    private IVoucherOrderService proxy;

    @PostConstruct
    /**
     *     类初始化之后，就立即执行
     */ private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderhandler());
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1. 获取用户
        Long userId = voucherOrder.getUserId();
        //2. 创建锁对象，作为兜底方案
        RLock redisLock = redissonClient.getLock("order:" + userId);
        //3. 获取锁
        boolean isLock = redisLock.tryLock();
        //4. 判断是否获取锁成功
        if (!isLock) {
            log.error("不允许重复下单!");
            return;
        }
        try {
            //5. 使用代理对象，由于这里是另外一个线程，
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            redisLock.unlock();
        }
    }

    public Result seckillVoucher(Long voucherId) {
        long orderId = redisIdWorker.nextId("order");

        //1. 执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString(),
                String.valueOf(orderId));
        //2. 判断返回值，并返回错误信息
        if (result.intValue() != 0) {
            return Result.fail(result.intValue() == 1 ? "库存不足" : "不能重复下单");
        }

//        //Lua脚本中实现了
//        //封装到voucherOrder中
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//        voucherOrder.setId(orderId);
//        //加入到阻塞队列
//        orderTasks.add(voucherOrder);

        //主线程获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3. 返回订单id
        return Result.ok(orderId);
    }
    /*
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //查询优惠券
        LambdaQueryWrapper<SeckillVoucher> lqw = new LambdaQueryWrapper<>();
        lqw.eq(SeckillVoucher::getVoucherId, voucherId);
        SeckillVoucher seckillVoucher = seckillVoucherService.getOne(lqw);
        //判断秒杀时间是否开始
        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) return Result.fail("秒杀还未开始，请耐心等待");
        //判断秒杀时间是否结束
        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) return Result.fail("秒杀已经结束！");
        //判断库存是否充足
        if (seckillVoucher.getStock() < 1) return Result.fail("优惠券已被抢光了哦，下次记得手速快点");

//        // 一人一单逻辑
//        Long userId = UserHolder.getUser().getId();
//        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
//        if (count > 0) {
//            return Result.fail("你已经抢过优惠券了哦");
//        }
//        //扣减库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock=stock-1")
//                .eq("voucher_id", voucherId)
////                .eq("stock",seckillVoucher.getStock())
//                .gt("stock", 0)
//                .update();
//
//        if (!success) return Result.fail("库存不足");
//        //创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(redisIdWorker.nextId("order"));
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//        //将订单数据保存到表中
//        save(voucherOrder);

        //当前方法被Spring的事务控制，如果你在内部加锁，可能会导致当前方法事务还没有提交，但是锁已经释放了
//        synchronized (UserHolder.getUser().getId()) {
//            return createVoucherOrder(voucherId);
//        }

        //

//        synchronized (userId.toString().intern()) {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }

//        // 创建锁对象
//        SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        // 获取锁对象
//        boolean isLock = redisLock.tryLock(120);

       RLock redissonClientLock =redissonClient.getLock("order:"+userId);
        boolean isLock=redissonClientLock.tryLock();


        // 加锁失败，说明当前用户开了多个线程抢优惠券，但是由于key是SETNX的，所以不能创建key，得等key的TTL到期或释放锁（删除key）
        if (!isLock) {
            return Result.fail("不允许抢多张优惠券");
        }
        try {
            // 获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
//            redisLock.unlock();

            redissonClientLock.unlock();
        }
    }
*/

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            return Result.fail("你已经抢过优惠券了哦");
        }

        //5. 扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherId)
//                .eq("stock",seckillVoucher.getStock())
                .gt("stock", 0).update();

        if (!success) return Result.fail("库存不足！");

        //6. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1 设置订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2 设置用户id

        voucherOrder.setUserId(userId);
        //6.3 设置代金券id
        voucherOrder.setVoucherId(voucherId);
        //7. 将订单数据保存到表中
        this.save(voucherOrder);
        //8. 返回订单id
        return Result.ok(orderId);
    }

//    private class VoucherOrderhandler implements Runnable {
//        @Override
//        public void run() {
//            try {
//                //1. 获取队列中的订单信息
//                VoucherOrder voucherOrder = orderTasks.take();
//                //2. 创建订单
//                handleVoucherOrder(voucherOrder);
//
//            } catch (InterruptedException e) {
//                log.error("订单处理异常", e);
//            }
//
//        }
//    }

    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        // 一人一单逻辑
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        synchronized (userId.toString().intern()) {
            int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
            if (count > 0) {
                log.error("你已经抢过优惠券了哦");
                return;
            }
            //5. 扣减库存
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
                log.error("库存不足");
            }
            //7. 将订单数据保存到表中
            save(voucherOrder);
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                //1. 获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().
                        read(Consumer.from("g1", "c1"),
                                StreamReadOptions.empty().count(1),
                                //ReadOffset.lastConsumed()底层就是 '>'
                                StreamOffset.create(queueName, ReadOffset.from("0")));
                //2. 判断消息是否获取成功
                if (records == null || records.isEmpty()) {
                    continue;
                }
                //3. 消息获取成功之后，我们需要将其转为对象
                MapRecord<String, Object, Object> record = records.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                //4. 获取成功，执行下单逻辑，将数据保存到数据库中
                handleVoucherOrder(voucherOrder);
                //5. 手动ACK，SACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
            } catch (Exception e) {

                log.error("处理pending-list异常");
                //如果怕异常多次出现，可以在这里休眠一会儿
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    private class VoucherOrderhandler implements Runnable {
        @Override
        public void run() {

            while (true) {
                try {
                    //1. 获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    //2.判断消息是否成功
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    //2.判断消息获取成功后，将其转为对象
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> values = record.getValue();

                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.获取成功，执行下单逻辑，将数据保存到数据库
                    handleVoucherOrder(voucherOrder);
                    //5.手动ACK            SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    //订单异常的处理方式我们封装成一个函数，避免代码太臃肿
                    handlePendingList();
                }

            }


        }
    }


}