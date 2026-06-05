package com.llsp.service;

import com.llsp.dto.Result;
import com.llsp.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogCommentsService extends IService<BlogComments> {

    Result getMyComments();

    Result saveComment(BlogComments comment);

    Result queryCommentsByBlogId(Long blogId);

    Result getCommentsToMe();

    Result deleteComment(Long commentId);
}
