package com.llsp.service.impl;

import com.llsp.entity.BlogComments;
import com.llsp.mapper.BlogCommentsMapper;
import com.llsp.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
