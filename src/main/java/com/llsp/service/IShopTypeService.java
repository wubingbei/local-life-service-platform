package com.llsp.service;

import com.llsp.dto.Result;
import com.llsp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopTypeService extends IService<ShopType> {

    Result queryList();
}
