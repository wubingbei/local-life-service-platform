package com.llsp.config;

import com.llsp.utils.LoginInterceptor;
import com.llsp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${llsp.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Value("${llsp.upload.dir:./uploads/imgs}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/imgs/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .order(0)
                .addPathPatterns("/**");
        registry.addInterceptor(new LoginInterceptor())
                .order(1)
                .excludePathPatterns(
                        // 登录相关
                        "/user/login",
                        "/user/code",
                        // 博客浏览
                        "/blog/hot",
                        "/blog/*",
                        "/blog/likes/*",
                        "/blog/of/user",
                        "/blog/search",
                        "/blog-comments/of/**",
                        // 商户浏览
                        "/shop/**",
                        "/shop-type/**",
                        "/shop-comments/of/**",
                        // 用户主页
                        "/user/info/*",
                        "/follow/or/not/*",
                        "/follow/common/*",
                        // 静态资源
                        "/imgs/**",
                        "/upload/**",
                        "/voucher/**",
                        "/static/**",
                        "/ai-chat.html"
                )
                .addPathPatterns("/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(!"*".equals(allowedOrigins))
                .maxAge(3600);
    }
}
