package com.llsp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llsp.dto.Result;
import com.llsp.dto.ScrollResult;
import com.llsp.dto.UserDTO;
import com.llsp.entity.Blog;
import com.llsp.entity.BlogComments;
import com.llsp.entity.Follow;
import com.llsp.entity.User;
import com.llsp.mapper.BlogCommentsMapper;
import com.llsp.mapper.BlogMapper;
import com.llsp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llsp.service.IFollowService;
import com.llsp.service.IUserService;
import com.llsp.utils.ContentSecurityUtils;
import com.llsp.utils.SystemConstants;
import com.llsp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.llsp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.llsp.utils.RedisConstants.FEED_KEY;
import static com.llsp.utils.RedisConstants.LOGIN_USER_KEY;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Resource
    private BlogCommentsMapper blogCommentsMapper;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlockliked(blog);
            this.queryBlogCommentsCount(blog);
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
        // 4.查询真实评论数
        queryBlogCommentsCount(blog);
        return Result.ok(blog);
    }

    private void isBlockliked(Blog blog) {
        blog.setIsLike(false);
        try {
            // 先从ThreadLocal取，再从请求头取（兼容/bog/hot等排除登录的路径）
            UserDTO user = UserHolder.getUser();
            if (user == null) {
                ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attrs == null) return;
                HttpServletRequest request = attrs.getRequest();
                String token = request.getHeader("authorization");
                if (StrUtil.isBlank(token)) return;
                String tokenKey = LOGIN_USER_KEY + token;
                Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
                if (userMap.isEmpty()) return;
                user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
            }
            Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), user.getId().toString());
            blog.setIsLike(score != null);
        } catch (Exception e) {
            blog.setIsLike(false);
        }
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
        // 2.校验必填字段
        if (StrUtil.isBlank(blog.getTitle())) {
            return Result.fail("标题不能为空");
        }
        if (StrUtil.isBlank(blog.getContent())) {
            return Result.fail("内容不能为空");
        }
        // 3.内容安全检测
        String titleErr = ContentSecurityUtils.validateTitle(blog.getTitle());
        if (titleErr != null) {
            return Result.fail(titleErr);
        }
        String contentErr = ContentSecurityUtils.validateContent(blog.getContent());
        if (contentErr != null) {
            return Result.fail(contentErr);
        }
        // 图片字段为空时设为空字符串，避免 null 值
        if (blog.getImages() == null) {
            blog.setImages("");
        }
        // 3.保存探店笔记
        boolean isSucccess = save(blog);
        if (!isSucccess) {
            return Result.fail("新增笔记失败！");
        }
        // 4.查询笔记作者的粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 5.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 获取粉丝id
            Long fansId = follow.getUserId();
            // 推送
            String key = FEED_KEY + fansId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 6.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long lastId, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询当前用户关注的所有用户ID
        List<Follow> follows = followService.query().eq("user_id", userId).list();
        if (follows == null || follows.isEmpty()) {
            return Result.ok();
        }
        List<Long> followingIds = follows.stream()
                .map(Follow::getFollowUserId)
                .collect(Collectors.toList());
        // 3.从数据库直接查询关注用户的博客（包括关注前的历史笔记），按时间倒序
        List<Blog> blogs = query()
                .in("user_id", followingIds)
                .lt(lastId != null && lastId > 0, "id", lastId)
                .orderByDesc("id")
                .last("limit " + SystemConstants.MAX_PAGE_SIZE)
                .list();
        if (blogs == null || blogs.isEmpty()) {
            return Result.ok();
        }
        // 4.补充用户信息、点赞状态、评论数
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlockliked(blog);
            queryBlogCommentsCount(blog);
        }
        // 5.封装并返回（minTime存最后一条ID作为下次游标）
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(blogs.get(blogs.size() - 1).getId());
        result.setOffset(1);
        return Result.ok(result);
    }

    @Override
    public Result queryMyBlog(Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = query()
                .eq("user_id", user.getId())
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户、点赞状态、评论数
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlockliked(blog);
            this.queryBlogCommentsCount(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogByUserId(Integer current, Long id) {
        // 根据用户查询
        Page<Blog> page = query()
                .eq("user_id", id)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户、点赞状态、评论数
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlockliked(blog);
            this.queryBlogCommentsCount(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result searchBlog(String keyword, Integer current) {
        Page<Blog> page = query()
                .like("title", keyword)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlockliked(blog);
            this.queryBlogCommentsCount(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result deleteBlog(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询笔记
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 3.判断是否为笔记作者
        if (!blog.getUserId().equals(userId)) {
            return Result.fail("无权删除该笔记！");
        }
        // 4.删除笔记
        boolean success = removeById(id);
        if (!success) {
            return Result.fail("删除失败！");
        }
        // 5.清理Redis中的点赞数据
        stringRedisTemplate.delete(BLOG_LIKED_KEY + id);
        return Result.ok();
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void queryBlogCommentsCount(Blog blog) {
        Long count = blogCommentsMapper.selectCount(
                new LambdaQueryWrapper<BlogComments>()
                        .eq(BlogComments::getBlogId, blog.getId())
                        .eq(BlogComments::getParentId, 0L)
        );
        blog.setComments(count != null ? count.intValue() : 0);
    }
}
