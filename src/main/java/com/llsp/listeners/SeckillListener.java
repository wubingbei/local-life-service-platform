package com.llsp.listeners;

import com.llsp.entity.VoucherOrder;
import com.llsp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Slf4j
@Component
public class SeckillListener {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private RedissonClient redissonClient;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "seckill.queue", durable = "true"),
            exchange = @Exchange(name = "seckill.topic", type = ExchangeTypes.TOPIC),
            key = "seckill.success"
    ))
    public void listenSeckillOrder(VoucherOrder voucherOrder) {
        try {
            handleVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("订单处理失败，订单信息：{}",voucherOrder);
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 失败，说明该用户的上一个订单还在处理
            log.error("不允许重复下单");
            return;
        }
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
}
