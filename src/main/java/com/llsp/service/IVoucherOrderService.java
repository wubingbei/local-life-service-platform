package com.llsp.service;

import com.llsp.dto.Result;
import com.llsp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    Result buyRegularVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);

    Result queryMyVouchers();
}
