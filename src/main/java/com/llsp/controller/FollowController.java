package com.llsp.controller;


import com.llsp.dto.Result;
import com.llsp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    @PutMapping("{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id) {
        return followService.followCommons(id);
    }

    @GetMapping("/my/followers")
    public Result getMyFollowers() {
        return followService.getMyFollowers();
    }

    @GetMapping("/my/following")
    public Result getMyFollowing() {
        return followService.getMyFollowing();
    }
}
