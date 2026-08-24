package com.llsp.controller;

import com.llsp.dto.Result;
import com.llsp.service.CacheStatsService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.Map;

@RestController
@RequestMapping("/cache")
public class CacheStatsController {

    @Resource
    private CacheStatsService cacheStatsService;

    @GetMapping("/stats")
    public Result getCacheStats() {
        Map<String, Object> stats = cacheStatsService.getCacheStats();
        return Result.ok(stats);
    }

    @PostMapping("/reset")
    public Result resetStats() {
        cacheStatsService.resetStats();
        return Result.ok("统计已重置");
    }
}