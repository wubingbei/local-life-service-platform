package com.llsp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表（Outbox Pattern）
 * 保证秒杀订单消息可靠投递到 RabbitMQ
 */
@Data
@TableName("tb_outbox_message")
public class OutboxMessage {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 消息唯一标识（UUID），用于幂等去重
     */
    private String messageId;

    /**
     * RabbitMQ 交换机名称
     */
    private String exchange;

    /**
     * RabbitMQ 路由键
     */
    private String routingKey;

    /**
     * 消息体（JSON 格式）
     */
    private String payload;

    /**
     * 消息状态：0-待发送，1-已发送，2-发送失败
     */
    private Integer status;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 最大重试次数
     */
    private Integer maxRetries;

    /**
     * 下次重试时间（指数退避）
     */
    private LocalDateTime nextRetryTime;

    /**
     * 最后一次错误信息
     */
    private String lastError;

    /**
     * 关联的订单 ID（便于对账）
     */
    private Long orderId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    // ========== 状态常量 ==========
    public static final int STATUS_PENDING = 0;   // 待发送
    public static final int STATUS_SENT = 1;       // 已发送
    public static final int STATUS_FAILED = 2;     // 发送失败
}
