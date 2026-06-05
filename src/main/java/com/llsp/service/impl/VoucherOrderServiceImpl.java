package com.llsp.service.impl;

import com.llsp.dto.Result;
import com.llsp.entity.SeckillVoucher;
import com.llsp.entity.Shop;
import com.llsp.entity.Voucher;
import com.llsp.entity.VoucherOrder;
import com.llsp.mapper.VoucherOrderMapper;
import com.llsp.service.ISeckillVoucherService;
import com.llsp.service.IShopService;
import com.llsp.service.IVoucherOrderService;
import com.llsp.service.IVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llsp.utils.RedisIdWorker;
import com.llsp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.*;

import static com.llsp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private IShopService shopService;

    // DefaultRedisScript<Long>: Spring提供的封装类，用来代表一个Lua脚本，指定脚本执行后返回的结果类型是Long
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static { // 在类第一次被加载时执行，且只执行一次。
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua")); // 指定脚本文件路径
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 秒杀券入口，快速响应用户
     * 执行Lua脚本判断库存和一人一单(Redis原子操作)
     * 仅处理秒杀券(type=1)
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 确保Redis中有库存数据
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        if (!stringRedisTemplate.hasKey(stockKey)) {
            SeckillVoucher sv = seckillVoucherService.getById(voucherId);
            if (sv != null && sv.getStock() != null) {
                stringRedisTemplate.opsForValue().set(stockKey, sv.getStock().toString());
            }
        }
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,         // 定义要在Redis中执行的Lua脚本逻辑
                Collections.emptyList(),    // 传递Lua脚本中使用的Redis键名(对应Lua里的KEYS[1], KEYS[2]...)
                voucherId.toString(), userId.toString() // 传递给Lua脚本的业务参数
                // ARGV[1] = 代金券ID ARGV[2] = 用户ID
        );
        // 判断用户资格
        int r = result.intValue();
        if (r != 0) {
            // 不为0，没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 为0，创建订单
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 把订单放入消息队列
        rabbitTemplate.convertAndSend("seckill.topic", "seckill.success", voucherOrder);
        // 返回订单id
        return Result.ok(orderId);
    }

    /**
     * 普通券购买入口，直接数据库下单
     * 无库存限制，仅保证一人一单
     */
    @Override
    @Transactional
    public Result buyRegularVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 一人一单检查
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("不能重复下单");
        }
        // 直接保存订单
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        try {
            save(voucherOrder);
        } catch (DataIntegrityViolationException e) {
            // 并发下重复下单被唯一索引拦截
            log.warn("用户 {} 重复购买普通券 {}", userId, voucherId);
            return Result.fail("不能重复下单");
        }
        return Result.ok(orderId);
    }

    /**
     * 事务内完成订单创建的核心逻辑
     * 检查是否重复购买(一人一单)
     * 扣减库存(乐观锁:stock > 0)
     * 保存订单到数据库
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户id和优惠券id
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();
        // 判断用户是否购买过
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            // count > 0,说明用户重复下单
            log.error("用户已经购买过一次!");
            return;
        }
        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1").eq("voucher_id", voucherId)
                .gt("stock", 0).update();
        // 判断是否成功
        if (!success) {
            log.error("库存不足!");
            return;
        }
        // 保存订单（唯一索引兜底）
        try {
            save(voucherOrder);
        } catch (DataIntegrityViolationException e) {
            log.error("用户 {} 重复购买优惠券 {}", userId, voucherId);
        }
    }

    @Override
    public Result queryMyVouchers() {
        Long userId = UserHolder.getUser().getId();
        List<VoucherOrder> orders = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .list();
        if (orders == null || orders.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (VoucherOrder order : orders) {
            Map<String, Object> map = new HashMap<>();
            map.put("orderId", order.getId());
            map.put("status", order.getStatus());
            map.put("createTime", order.getCreateTime());
            // 查优惠券详情
            Voucher voucher = voucherService.getById(order.getVoucherId());
            if (voucher != null) {
                map.put("voucherId", voucher.getId());
                map.put("title", voucher.getTitle());
                map.put("subTitle", voucher.getSubTitle());
                map.put("payValue", voucher.getPayValue());
                map.put("actualValue", voucher.getActualValue());
                map.put("rules", voucher.getRules());
                // 查店铺名称
                Shop shop = shopService.getById(voucher.getShopId());
                map.put("shopName", shop != null ? shop.getName() : "未知店铺");
            }
            result.add(map);
        }
        return Result.ok(result);
    }
}
