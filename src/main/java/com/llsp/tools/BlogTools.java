package com.llsp.tools;

import com.llsp.dto.Result;
import com.llsp.service.IBlogService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
public class BlogTools {
    
    @Resource
    private IBlogService blogService;
    
    @Tool(description = "查询热门探店笔记列表，帮助用户了解其他用户的探店体验")
    public String queryHotBlogs(@ToolParam(description = "页码，从1开始") Integer current) {
        Result result = blogService.queryHotBlog(current);
        return "热门探店笔记：" + result;
    }
    
    @Tool(description = "根据笔记ID查询笔记详情")
    public String queryBlogById(@ToolParam(description = "笔记ID") Long blogId) {
        Result result = blogService.queryBlogById(blogId);
        return "笔记详情：" + result;
    }
    
    @Tool(description = "查询关注用户的探店笔记")
    public String queryFollowBlogs(@ToolParam(description = "最大ID") Long max,
                                    @ToolParam(description = "偏移量") Integer offset) {
        Result result = blogService.queryBlogOfFollow(max, offset);
        return "关注的笔记：" + result;
    }
}
