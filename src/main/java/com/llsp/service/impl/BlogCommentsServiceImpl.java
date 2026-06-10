package com.llsp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llsp.dto.Result;
import com.llsp.entity.Blog;
import com.llsp.entity.BlogComments;
import com.llsp.mapper.BlogCommentsMapper;
import com.llsp.service.IBlogCommentsService;
import com.llsp.service.IBlogService;
import com.llsp.utils.ContentSecurityUtils;
import com.llsp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IBlogService blogService;

    @Override
    public Result getMyComments() {
        Long userId = UserHolder.getUser().getId();
        List<BlogComments> comments = query().eq("user_id", userId)
                .orderByDesc("create_time")
                .list();
        if (comments == null || comments.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (BlogComments c : comments) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("content", c.getContent());
            map.put("liked", c.getLiked());
            map.put("blogId", c.getBlogId());
            map.put("createTime", c.getCreateTime());
            Result blogResult = blogService.queryBlogById(c.getBlogId());
            if (blogResult.getData() != null) {
                Blog blog = (Blog) blogResult.getData();
                map.put("blogTitle", blog.getTitle());
            } else {
                map.put("blogTitle", "查看笔记");
            }
            result.add(map);
        }
        return Result.ok(result);
    }

    @Override
    public Result saveComment(BlogComments comment) {
        // 内容安全检测
        String err = ContentSecurityUtils.validateContent(comment.getContent());
        if (err != null) {
            return Result.fail(err);
        }
        Long userId = UserHolder.getUser().getId();
        comment.setUserId(userId);
        comment.setParentId(0L);
        comment.setAnswerId(0L);
        comment.setCreateTime(java.time.LocalDateTime.now());
        comment.setLiked(0);
        comment.setStatus(false);
        save(comment);
        // 更新博客评论数
        blogService.update(new LambdaUpdateWrapper<Blog>()
                .eq(Blog::getId, comment.getBlogId())
                .setSql("comments = comments + 1"));
        return Result.ok(comment.getId());
    }

    @Override
    public Result queryCommentsByBlogId(Long blogId) {
        List<BlogComments> comments = query().eq("blog_id", blogId)
                .eq("parent_id", 0)
                .orderByDesc("create_time")
                .list();
        if (comments == null || comments.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (BlogComments c : comments) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("userId", c.getUserId());
            map.put("content", c.getContent());
            map.put("liked", c.getLiked());
            map.put("createTime", c.getCreateTime());
            // 查出用户头像和昵称
            com.llsp.entity.User user = userService.getById(c.getUserId());
            if (user != null) {
                String nickName = user.getNickName();
                map.put("userName", nickName != null && !nickName.isEmpty() ? nickName : ("用户" + user.getId()));
                map.put("userIcon", user.getIcon());
            } else {
                map.put("userName", "用户" + c.getUserId());
                map.put("userIcon", "");
            }
            result.add(map);
        }
        return Result.ok(result);
    }

    @Override
    public Result getCommentsToMe() {
        Long userId = UserHolder.getUser().getId();
        // 查出我发布的所有博客ID
        List<Long> myBlogIds = blogService.query()
                .eq("user_id", userId)
                .list()
                .stream()
                .map(com.llsp.entity.Blog::getId)
                .collect(java.util.stream.Collectors.toList());
        if (myBlogIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 查出这些博客的评论（排除自己的评论）
        List<BlogComments> comments = query()
                .in("blog_id", myBlogIds)
                .ne("user_id", userId)
                .orderByDesc("create_time")
                .list();
        if (comments == null || comments.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (BlogComments c : comments) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("userId", c.getUserId());
            map.put("blogId", c.getBlogId());
            map.put("content", c.getContent());
            map.put("createTime", c.getCreateTime());
            com.llsp.entity.User user = userService.getById(c.getUserId());
            if (user != null) {
                String nickName = user.getNickName();
                map.put("userName", nickName != null && !nickName.isEmpty() ? nickName : ("用户" + user.getId()));
                map.put("userIcon", user.getIcon());
            } else {
                map.put("userName", "用户" + c.getUserId());
                map.put("userIcon", "");
            }
            // 查博客标题
            com.llsp.entity.Blog blog = blogService.getById(c.getBlogId());
            if (blog != null) {
                map.put("blogTitle", blog.getTitle());
            }
            result.add(map);
        }
        return Result.ok(result);
    }

    @Override
    public Result deleteComment(Long commentId) {
        Long userId = UserHolder.getUser().getId();
        BlogComments comment = getById(commentId);
        if (comment == null) {
            return Result.fail("评论不存在");
        }
        if (!comment.getUserId().equals(userId)) {
            return Result.fail("不能删除他人的评论");
        }
        removeById(commentId);
        // 更新博客评论数
        blogService.update(new LambdaUpdateWrapper<Blog>()
                .eq(Blog::getId, comment.getBlogId())
                .setSql("comments = GREATEST(comments - 1, 0)"));
        return Result.ok();
    }

    @Resource
    private com.llsp.service.IUserService userService;
}
