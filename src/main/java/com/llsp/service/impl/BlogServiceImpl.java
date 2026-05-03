package com.llsp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llsp.dto.Result;
import com.llsp.dto.ScrollResult;
import com.llsp.dto.UserDTO;
import com.llsp.entity.Blog;
import com.llsp.entity.Follow;
import com.llsp.entity.User;
import com.llsp.mapper.BlogMapper;
import com.llsp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llsp.service.IFollowService;
import com.llsp.service.IUserService;
import com.llsp.utils.SystemConstants;
import com.llsp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.llsp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.llsp.utils.RedisConstants.FEED_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlockliked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户
        queryBlogUser(blog);
        // 3.查询blog是否被点赞
        isBlockliked(blog);
        return Result.ok(blog);
    }

    private void isBlockliked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前用户是否已经点赞
        Double score = stringRedisTemplate.opsForZSet().score("blog:liked:" + blog.getId(), userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前用户是否已经点赞
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + id, userId.toString());
        if (score == null) {
            // 3.如果未点赞，则点赞
            // 3.1 数据库点赞数量+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2 保存用户到Redis的sortedSet集合 zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + id, userId.toString(), System.currentTimeMillis());
            }
        }else {
            // 4.如果已点赞，则取消点赞
            // 4.1 数据库点赞数量-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2 把用户从Redis的Set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + id, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrang key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idstr = StrUtil.join(",", ids);
        // 3.根据用户ID查询用户
        List<UserDTO> userDTOS = userService.query().in("id", ids)
                .last("order by field(id," + idstr + ")").list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        boolean isSucccess = save(blog);
        if (!isSucccess) {
            return Result.fail("新增笔记失败！");
        }
        // 3.查询笔记作者的粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 获取粉丝id
            Long fansId = follow.getUserId();
            // 推送
            String key = FEED_KEY + fansId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 3.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key max min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId，minTime(时间戳)，offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 4.1 获取id
            ids.add(Long.valueOf( typedTuple.getValue()));
            // 4.2 获取分数(时间戳)
            long time = typedTuple.getScore().longValue();
            if (time == minTime) {
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        // 5.根据 id 查询 blog
        if (ids == null || ids.isEmpty()) {
            return Result.ok();
        }
        String idstr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("order by field(id," + idstr + ")").list();
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlockliked(blog);
        }
        // 6.封装并返回
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setOffset(os);
        result.setMinTime(minTime);
        return Result.ok(result);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
