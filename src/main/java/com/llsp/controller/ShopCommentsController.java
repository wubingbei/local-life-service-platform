package com.llsp.controller;

import com.llsp.dto.Result;
import com.llsp.entity.ShopComments;
import com.llsp.service.IShopCommentsService;
import com.llsp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/shop-comments")
public class ShopCommentsController {

    @Resource
    private IShopCommentsService shopCommentsService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostMapping
    public Result saveComment(@RequestBody ShopComments comment) {
        Long userId = UserHolder.getUser().getId();
        if (!Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember("whitelist:post", userId.toString()))) {
            return Result.fail("暂无发布权限");
        }
        return shopCommentsService.saveComment(comment);
    }

    @GetMapping("/of/{shopId}")
    public Result queryCommentsByShopId(@PathVariable Long shopId) {
        return shopCommentsService.queryCommentsByShopId(shopId);
    }

    @DeleteMapping("/{id}")
    public Result deleteComment(@PathVariable Long id) {
        return shopCommentsService.deleteComment(id);
    }
}
