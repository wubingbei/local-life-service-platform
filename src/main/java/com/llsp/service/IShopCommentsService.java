package com.llsp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.llsp.dto.Result;
import com.llsp.entity.ShopComments;

public interface IShopCommentsService extends IService<ShopComments> {

    Result saveComment(ShopComments comment);

    Result queryCommentsByShopId(Long shopId);

    Result deleteComment(Long id);
}
