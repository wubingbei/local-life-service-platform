package com.llsp.controller;


import com.llsp.dto.Result;
import com.llsp.entity.BlogComments;
import com.llsp.service.IBlogCommentsService;
import com.llsp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("/my")
    public Result getMyComments() {
        return blogCommentsService.getMyComments();
    }

    @PostMapping
    public Result saveComment(@RequestBody BlogComments comment) {
        Long userId = UserHolder.getUser().getId();
        if (!Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember("whitelist:post", userId.toString()))) {
            return Result.fail("暂无发布权限");
        }
        return blogCommentsService.saveComment(comment);
    }

    @GetMapping("/of/{blogId}")
    public Result queryCommentsByBlogId(@PathVariable Long blogId) {
        return blogCommentsService.queryCommentsByBlogId(blogId);
    }

    @GetMapping("/to-me")
    public Result getCommentsToMe() {
        return blogCommentsService.getCommentsToMe();
    }

    @DeleteMapping("/{id}")
    public Result deleteComment(@PathVariable Long id) {
        return blogCommentsService.deleteComment(id);
    }
}
