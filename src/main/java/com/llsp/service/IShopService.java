package com.llsp.service;

import com.llsp.dto.Result;
import com.llsp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);

    Result queryShopsWithSort(Integer typeId, String sortBy, Integer current);

    Result queryShopByName(String name, Integer current, Integer size);
}
