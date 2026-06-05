package com.llsp.controller;

import com.llsp.dto.Result;
import com.llsp.entity.ShopComments;
import com.llsp.service.IShopCommentsService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/shop-comments")
public class ShopCommentsController {

    @Resource
    private IShopCommentsService shopCommentsService;

    @PostMapping
    public Result saveComment(@RequestBody ShopComments comment) {
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
