package com.llsp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llsp.dto.LoginFormDTO;
import com.llsp.dto.Result;
import com.llsp.entity.User;

public interface IUserService extends IService<User> {

    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm);

    Result sign();

    Result signCount();
}
