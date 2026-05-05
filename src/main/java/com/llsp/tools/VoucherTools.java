package com.llsp.tools;

import com.llsp.dto.Result;
import com.llsp.service.IVoucherService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
public class VoucherTools {
    
    @Resource
    private IVoucherService voucherService;
    
    @Tool(description = "查询指定商铺的优惠券列表，包括普通优惠券和秒杀优惠券")
    public String queryVouchersByShop(@ToolParam(description = "商铺ID") Long shopId) {
        Result result = voucherService.queryVoucherOfShop(shopId);
        return "优惠券列表：" + result;
    }
}
