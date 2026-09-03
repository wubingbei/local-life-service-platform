package com.llsp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.llsp.entity.SeckillVoucher;
import com.llsp.entity.VoucherOrder;
import com.llsp.mapper.VoucherOrderMapper;
import com.llsp.service.ISeckillVoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoucherOrderServiceImplTest {

    /**
     * 扣库存成功后 save 撞唯一索引，应抛异常触发事务回滚（而非静默吞掉导致库存白扣）
     */
    @Test
    @SuppressWarnings("unchecked")
    void createVoucherOrder_rethrowsDataIntegrityViolation() {
        VoucherOrderServiceImpl service = spy(new VoucherOrderServiceImpl());
        VoucherOrderMapper baseMapper = mock(VoucherOrderMapper.class);
        ISeckillVoucherService seckillVoucherService = mock(ISeckillVoucherService.class);
        // seckillVoucherService.update() 返回 UpdateChainWrapper（String 列版本），链上 setSql/eq/gt 均返回自身
        UpdateChainWrapper<SeckillVoucher> chain = mock(UpdateChainWrapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", baseMapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);

        // 一人一单检查：无重复
        when(baseMapper.selectCount(any())).thenReturn(0L);
        // 扣库存链：update().setSql().eq().gt().update() 返回 true
        when(seckillVoucherService.update()).thenReturn(chain);
        when(chain.setSql(anyString())).thenReturn(chain);
        when(chain.eq(anyString(), any())).thenReturn(chain);
        when(chain.gt(anyString(), any())).thenReturn(chain);
        when(chain.update()).thenReturn(true);
        // save 撞唯一索引
        doThrow(new DataIntegrityViolationException("duplicate key")).when(service).save(any(VoucherOrder.class));

        VoucherOrder order = new VoucherOrder();
        order.setId(1L);
        order.setUserId(1L);
        order.setVoucherId(1L);

        assertThrows(DataIntegrityViolationException.class, () -> service.createVoucherOrder(order));
    }
}
