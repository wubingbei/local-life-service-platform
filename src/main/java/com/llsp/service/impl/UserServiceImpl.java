package com.llsp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.llsp.dto.LoginFormDTO;
import com.llsp.dto.Result;
import com.llsp.dto.UserDTO;
import com.llsp.entity.User;
import com.llsp.entity.UserInfo;
import com.llsp.mapper.UserMapper;
import com.llsp.service.IUserInfoService;
import com.llsp.service.IUserService;
import com.llsp.utils.RegexUtils;
import com.llsp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import org.springframework.security.crypto.password.PasswordEncoder;


import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.llsp.utils.RedisConstants.*;
import static com.llsp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private PasswordEncoder passwordEncoder;

    /**
     * 发送短信验证码（未配置短信则日志打印）
     * @param phone 手机号
     * @return Result
     */
    @Override
    public Result sendCode(String phone) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        // 限流检查：60秒内只能发送一次
        String limitKey = SMS_LIMIT_KEY + phone;
        Boolean isLimit = stringRedisTemplate.opsForValue().setIfAbsent(limitKey, "1", SMS_LIMIT_TTL, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(isLimit)) {
            Long ttl = stringRedisTemplate.getExpire(limitKey, TimeUnit.SECONDS);
            return Result.fail("验证码发送过于频繁，请" + ttl + "秒后再试！");
        }
        // 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 返回验证码给前端
        return Result.ok(code);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误！");
        }
        String code = loginForm.getCode();
        String password = loginForm.getPassword();

        User user;
        // 判断登录方式：验证码 or 密码
        if (code != null && !code.isEmpty()) {
            // ========== 验证码登录 ==========
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            if (cacheCode == null || !code.equals(cacheCode)) {
                return Result.fail("验证码错误！");
            }
            user = query().eq("phone", phone).one();
            if (user == null) {
                // 新用户，自动注册
                user = createUserWithPhone(phone);
            }
        } else if (password != null && !password.isEmpty()) {
            // ========== 密码登录 ==========
            user = query().eq("phone", phone).one();
            if (user == null) {
                return Result.fail("用户不存在，请先使用验证码登录！");
            }
            if (user.getPassword() == null) {
                return Result.fail("该账号未设置密码，请使用验证码登录！");
            }
            if (!passwordEncoder.matches(password, user.getPassword())) {
                // BCrypt 不匹配，尝试明文匹配（兼容旧数据）
                if (!password.equals(user.getPassword())) {
                    return Result.fail("密码错误！");
                }
                // 明文匹配成功，自动升级为 BCrypt 哈希
                user.setPassword(passwordEncoder.encode(password));
                updateById(user);
                log.info("用户 {} 密码已从明文自动升级为BCrypt", phone);
            }
        } else {
            return Result.fail("请输入验证码或密码！");
        }
        // 生成token
        String token = UUID.randomUUID().toString(true);
        // 把User对象转为HashMap存储 user->userDTO->userMap
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 保存到redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        // 设置过期时间
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.HOURS);
        return Result.ok(token);
    }

    /**
     * 签到
     */
    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long user = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + user + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入redis setbit key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 统计签到
     */
    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long user = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + user + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截至今天为止的所有签到记录，返回的是一个十进制的数字 bitfield sign:user:1:202203 get u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0L) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 让这个数字与1做与运算，得到数字的最后一个bit位,判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果为1，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 通过电话创建用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //保存用户
        save(user);
        // 同步创建 tb_user_info 记录，避免后续查询返回空导致前端硬编码兜底
        UserInfo info = new UserInfo();
        info.setUserId(user.getId());
        userInfoService.save(info);
        return user;
    }

}
