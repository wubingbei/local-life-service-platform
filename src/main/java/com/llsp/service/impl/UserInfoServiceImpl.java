package com.llsp.service.impl;

import com.llsp.entity.UserInfo;
import com.llsp.mapper.UserInfoMapper;
import com.llsp.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
