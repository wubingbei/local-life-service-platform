package com.llsp.service.impl;

import com.llsp.entity.Shop;
import com.llsp.utils.BloomFilterHelper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.llsp.utils.RedisConstants.BLOOM_SHOP_KEY;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ShopServiceImplTest {

    @Test
    void createShop_addsToBloomFilter_whenEnabled() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        BloomFilterHelper bloomFilterHelper = mock(BloomFilterHelper.class);
        ReflectionTestUtils.setField(service, "bloomFilterHelper", bloomFilterHelper);
        ReflectionTestUtils.setField(service, "bloomFilterEnabled", true);

        Shop shop = new Shop();
        shop.setId(999L);
        doReturn(true).when(service).save(shop);

        service.createShop(shop);

        verify(bloomFilterHelper).addElement(BLOOM_SHOP_KEY, 999L);
    }

    @Test
    void createShop_skipsBloomFilter_whenDisabled() {
        ShopServiceImpl service = spy(new ShopServiceImpl());
        BloomFilterHelper bloomFilterHelper = mock(BloomFilterHelper.class);
        ReflectionTestUtils.setField(service, "bloomFilterHelper", bloomFilterHelper);
        ReflectionTestUtils.setField(service, "bloomFilterEnabled", false);

        Shop shop = new Shop();
        shop.setId(999L);
        doReturn(true).when(service).save(shop);

        service.createShop(shop);

        verify(bloomFilterHelper, never()).addElement(anyString(), anyLong());
    }
}
