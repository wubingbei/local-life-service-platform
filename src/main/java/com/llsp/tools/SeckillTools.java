package com.llsp.tools;

import com.llsp.dto.Result;
import com.llsp.service.IVoucherOrderService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
public class SeckillTools {
    
    @Resource
    private IVoucherOrderService voucherOrderService;
    
    @Tool(description = "用户抢购秒杀优惠券，需要先确认用户ID和优惠券ID")
    public String seckillVoucher(@ToolParam(description = "秒杀优惠券ID") Long voucherId,
                                  @ToolParam(description = "用户ID") Long userId) {
        Result result = voucherOrderService.seckillVoucher(voucherId);
        return "秒杀结果：" + result;
    }
}
