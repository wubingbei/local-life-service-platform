package com.llsp.service.impl;

import com.llsp.dto.Result;
import com.llsp.entity.ShopComments;
import com.llsp.entity.User;
import com.llsp.mapper.ShopCommentsMapper;
import com.llsp.service.IShopCommentsService;
import com.llsp.service.IUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llsp.utils.ContentSecurityUtils;
import com.llsp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ShopCommentsServiceImpl extends ServiceImpl<ShopCommentsMapper, ShopComments> implements IShopCommentsService {

    @Resource
    private IUserService userService;

    @Override
    public Result saveComment(ShopComments comment) {
        // 内容安全检测
        String err = ContentSecurityUtils.validateContent(comment.getContent());
        if (err != null) {
            return Result.fail(err);
        }
        Long userId = UserHolder.getUser().getId();
        comment.setUserId(userId);
        comment.setCreateTime(java.time.LocalDateTime.now());
        boolean success = save(comment);
        if (!success) {
            return Result.fail("评论失败！");
        }
        return Result.ok(comment.getId());
    }

    @Override
    public Result queryCommentsByShopId(Long shopId) {
        List<ShopComments> list = query()
                .eq("shop_id", shopId)
                .orderByDesc("create_time")
                .list();
        if (list == null || list.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ShopComments c : list) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("shopId", c.getShopId());
            map.put("userId", c.getUserId());
            map.put("content", c.getContent());
            map.put("createTime", c.getCreateTime());
            // 查出用户头像和昵称
            User user = userService.getById(c.getUserId());
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
    public Result deleteComment(Long id) {
        Long userId = UserHolder.getUser().getId();
        ShopComments comment = getById(id);
        if (comment == null) {
            return Result.fail("评论不存在！");
        }
        if (!comment.getUserId().equals(userId)) {
            return Result.fail("无权删除该评论！");
        }
        removeById(id);
        return Result.ok();
    }
}
