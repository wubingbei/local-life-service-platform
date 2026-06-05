package com.llsp.controller;


import com.llsp.dto.Result;
import com.llsp.entity.BlogComments;
import com.llsp.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    @GetMapping("/my")
    public Result getMyComments() {
        return blogCommentsService.getMyComments();
    }

    @PostMapping
    public Result saveComment(@RequestBody BlogComments comment) {
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
