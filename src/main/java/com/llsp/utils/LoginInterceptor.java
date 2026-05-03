package com.llsp.utils;

import org.springframework.web.servlet.HandlerInterceptor;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.判断是否需要拦截（Treadlocal中是否用用户）
        if (UserHolder.getUser() == null){
            //没有，需要拦截，设置状态码
            response.setStatus(401);
            return false;
        }
        //放行
        return true;
    }

}
