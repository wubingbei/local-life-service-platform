package com.llsp.tools;

import com.llsp.dto.Result;
import com.llsp.service.IShopService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
public class ShopTools {
    
    @Resource
    private IShopService shopService;
    
    @Tool(description = "根据商铺类型查询商铺列表，用于帮助用户找到符合需求的商铺")
    public String queryShopsByType(@ToolParam(description = "商铺类型ID，1-美食 2-休闲娱乐 3-丽人 4-酒店") Integer typeId,
                                    @ToolParam(description = "商圈名称，如陆家嘴、静安寺等") String area) {
        Result result = shopService.queryShopByType(typeId, 1, null, null);
        return "商铺查询结果：" + result;
    }
    
    @Tool(description = "根据商铺ID查询商铺详细信息")
    public String queryShopById(@ToolParam(description = "商铺ID") Long shopId) {
        Result result = shopService.queryById(shopId);
        return "商铺详情：" + result;
    }
    
    @Tool(description = "根据商铺类型和排序条件查询商铺，支持按评分、销量、均价排序")
    public String queryShopsWithSort(@ToolParam(description = "商铺类型ID") Integer typeId,
                                      @ToolParam(description = "排序方式：score-按评分排序，sold-按销量排序，price-按价格排序") String sortBy,
                                      @ToolParam(description = "页码，从1开始") Integer current) {
        Result result = shopService.queryShopsWithSort(typeId, sortBy, current);
        return "排序后的商铺列表：" + result;
    }
}
