package com.llsp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.llsp.dto.LoginFormDTO;
import com.llsp.dto.Result;
import com.llsp.dto.UserDTO;
import com.llsp.entity.User;
import com.llsp.entity.UserInfo;
import com.llsp.service.IUserInfoService;
import com.llsp.service.IUserService;
import com.llsp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private PasswordEncoder passwordEncoder;

    /**
     * 发送短信验证码
     * @param phone 手机号
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm){
        //实现登录功能
        return userService.login(loginForm);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(){
        // 实现登出功能
        UserHolder.removeUser();
        return Result.ok();
    }

    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    @PutMapping("/info")
    public Result updateInfo(@RequestBody Map<String, Object> params){
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 更新 tb_user 表（昵称、头像）
        User user = new User();
        user.setId(userId);
        boolean updateUser = false;
        if (params.containsKey("nickName")) {
            user.setNickName((String) params.get("nickName"));
            updateUser = true;
        }
        if (params.containsKey("icon")) {
            user.setIcon((String) params.get("icon"));
            updateUser = true;
        }
        if (params.containsKey("phone")) {
            user.setPhone((String) params.get("phone"));
            updateUser = true;
        }
        if (params.containsKey("password")) {
            user.setPassword(passwordEncoder.encode((String) params.get("password")));
            updateUser = true;
        }
        if (updateUser) userService.updateById(user);
        // 更新 tb_user_info 表
        boolean updateInfo = false;
        UserInfo info = new UserInfo();
        info.setUserId(userId);
        if (params.containsKey("city")) {
            info.setCity((String) params.get("city"));
            updateInfo = true;
        }
        if (params.containsKey("introduce")) {
            info.setIntroduce((String) params.get("introduce"));
            updateInfo = true;
        }
        if (params.containsKey("gender")) {
            info.setGender((Boolean) params.get("gender"));
            updateInfo = true;
        }
        if (params.containsKey("birthday")) {
            info.setBirthday(java.time.LocalDate.parse((String) params.get("birthday")));
            updateInfo = true;
        }
        // 只有当有字段需要更新时才执行 saveOrUpdate
        if (updateInfo) {
            userInfoService.saveOrUpdate(info);
        }
        return Result.ok();
    }

    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }
}
