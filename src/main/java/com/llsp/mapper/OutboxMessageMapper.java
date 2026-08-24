package com.llsp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llsp.entity.OutboxMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 本地消息表 Mapper
 */
public interface OutboxMessageMapper extends BaseMapper<OutboxMessage> {

    /**
     * 查询待发送的消息（状态为 PENDING 且重试时间已到）
     */
    @Select("SELECT * FROM tb_outbox_message WHERE status = 0 AND next_retry_time <= #{now} ORDER BY next_retry_time ASC LIMIT #{limit}")
    List<OutboxMessage> selectPendingMessages(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 标记消息为已发送
     */
    @Update("UPDATE tb_outbox_message SET status = 1, update_time = NOW() WHERE id = #{id}")
    int markAsSent(@Param("id") Long id);

    /**
     * 按 messageId 标记消息为已发送（供 Publisher Confirm 回调使用）
     */
    @Update("UPDATE tb_outbox_message SET status = 1, update_time = NOW() WHERE message_id = #{messageId}")
    int markAsSentByMessageId(@Param("messageId") String messageId);

    /**
     * 标记消息为失败（超过最大重试次数）
     */
    @Update("UPDATE tb_outbox_message SET status = 2, last_error = #{error}, update_time = NOW() WHERE id = #{id}")
    int markAsFailed(@Param("id") Long id, @Param("error") String error);

    /**
     * 更新重试信息（指数退避）
     */
    @Update("UPDATE tb_outbox_message SET retry_count = retry_count + 1, next_retry_time = #{nextRetry}, update_time = NOW() WHERE id = #{id}")
    int updateRetryInfo(@Param("id") Long id, @Param("nextRetry") LocalDateTime nextRetry);
}
