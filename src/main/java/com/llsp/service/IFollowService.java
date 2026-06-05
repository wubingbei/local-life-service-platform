package com.llsp.service;

import com.llsp.dto.Result;
import com.llsp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result followCommons(Long id);

    Result getMyFollowers();

    Result getMyFollowing();
}
