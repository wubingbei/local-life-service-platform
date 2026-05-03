package com.llsp.service.impl;

import cn.hutool.json.JSONUtil;
import com.llsp.dto.Result;
import com.llsp.entity.ShopType;
import com.llsp.mapper.ShopTypeMapper;
import com.llsp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        // 1.从redis查询
        List<String> typelist = stringRedisTemplate.opsForList().range("cache:shoptype", 0, -1);
        // 2.判断是否存在
        List<ShopType> shopTypeList = new ArrayList<>();
        if (typelist != null && !typelist.isEmpty()) {
            // 3.存在，直接返回
            for (String s : typelist){
                shopTypeList.add(JSONUtil.toBean(s, ShopType.class));
            }
            return Result.ok(shopTypeList);
        }
        // 4.不存在，查询数据库
        List<ShopType> typelist1 = query().orderByAsc("sort").list();
        // 5.不存在，返回错误
        if (typelist1 == null){
            return Result.fail("店铺种类不存在");
        }
        // 6.存在，写入redis
        for (ShopType shopType : typelist1){
            stringRedisTemplate.opsForList().rightPush("cache:shoptype", JSONUtil.toJsonStr(shopType));
        }
        // 7.返回
        return Result.ok(typelist1);
    }
}
