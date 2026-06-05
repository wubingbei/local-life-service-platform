package com.llsp.controller;

import com.llsp.dto.Result;
import com.llsp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;


@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @PostMapping("buy/{id}")
    public Result buyRegularVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.buyRegularVoucher(voucherId);
    }

    @GetMapping("/my")
    public Result queryMyVouchers() {
        return voucherOrderService.queryMyVouchers();
    }
}
