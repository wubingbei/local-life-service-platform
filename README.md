# 本地生活服务平台 | 高并发秒杀 + 多级缓存 + 可靠消息 + AI 智能助手

本项目是一款类似于大众点评的本地生活服务平台，基于 **Spring Boot 3.2 + JDK 17 + Redis** 构建，覆盖商户查询、优惠券秒杀、探店笔记、AI 智能客服等核心场景。针对高并发读写、热点缓存、秒杀超卖、消息可靠投递等经典分布式问题做了全链路设计，并深度集成 **Spring AI + Function Calling**，实现自然语言驱动业务查询。

## ✨ 技术栈

| 领域 | 选型 |
|---|---|
| 后端框架 | Spring Boot 3.2 / Spring MVC / MyBatis-Plus 3.5.6 |
| 存储 | MySQL 8.0+ / Redis 6.0 |
| 消息队列 | RabbitMQ 3.0+（Publisher Confirm + DLX 死信队列） |
| 多级缓存 | Caffeine（本地） + Redisson RBloomFilter + Redis |
| 并发控制 | Lua 脚本（原子）、Redisson（RLock / RRateLimiter）、Redis SETNX |
| AI | Spring AI（Function Calling + SSE 流式 + 熔断降级） |
| 安全 | BCrypt + 内容安全过滤（XSS / 敏感词） + 双拦截器鉴权 |

## 🚀 核心设计

### 1. 四级缓存与热点防护

针对商户详情这类高频读场景，构建 **Caffeine → 布隆过滤器 → Redis → MySQL** 的渐进式缓存体系，分别化解缓存三大经典问题：

- **缓存穿透（查不存在的 key）**
  - 入口用 **Redisson RBloomFilter** 预判：不存在的店铺 ID 在布隆层直接返回，Redis / MySQL 完全不受冲击。
  - 兜底用**空值缓存**：真实不存在的 key 写入 `""` 占位（TTL 2 分钟），避免重复穿透。
  - 布隆过滤器启动时从 DB 全量 `rebuild`，新建店铺在落库后同步写入。
- **缓存击穿（热点 key 失效瞬间大量并发）**
  - Redis 层采用**混合读路径**：
    - **命中且未逻辑过期** → 直接返回；
    - **命中但已逻辑过期** → 抢锁后提交 `cacheRebuildExecutor` 线程池**异步重建**，当前请求返回旧值（用户无感知、主线程不阻塞）；
    - **未命中（key 不存在）** → `SETNX` 互斥锁 + 双重检测，仅一个线程查 DB 回填，其余线程自旋等待。
- **缓存雪崩（大量 key 同时失效）**
  - 写入时对 TTL 加 **±20% 随机偏移**（`ThreadLocalRandom`），打散集体过期窗口。
- **一致性**
  - 采用 **Cache-Aside** 策略：商户 `update` / `delete` 时同步失效 Caffeine 与 Redis 两级缓存。
  - 热点数据 `@PostConstruct` 预热 Top 50 热店到本地缓存与 Redis，削平冷启动 DB 峰值。

> 缓存重建线程池为有界队列 + 命名守护线程 + **拒绝策略（丢弃并仅告警）**，重建失败不影响接口响应。

### 2. 高并发优惠券秒杀

秒杀链路遵循「**原子扣减 → 限流 → 异步削峰 → 可靠落库**」设计：

- **原子库存扣减（Lua 脚本）**：库存判断、一人一单校验、库存扣减、用户记录写入在 Redis 单线程中**一次原子完成**，避免分布式锁竞争；
- **分布式限流**：秒杀入口用 **Redisson RRateLimiter 令牌桶** + 自定义 `@RateLimit` 注解 + AOP 横切（100 QPS），**Fail-open** 设计（Redis 异常时放行，限流组件自身不成为可用性瓶颈）；
- **异步削峰**：Lua 校验通过即生成订单号（**RedisIdWorker**：时间戳 + Redis 自增序列，按天重置，适合分布式环境）并投递 RabbitMQ，由 `SeckillListener` 异步落库，不阻塞用户；
- **消费端并发控制**：落库时按 `userId` 加 **Redisson RLock**，防止同一用户的并发消息重入创建重复订单；
- **数据库兜底**：乐观锁（`WHERE stock > 0`）防超卖 + `(user_id, voucher_id)` 唯一索引防重复。

### 3. 可靠消息一致性闭环（Outbox + 死信 + 幂等 + 对账）

秒杀「扣库存」与「订单落库」跨越 Redis 与 DB 两个存储，仅靠直接发 MQ 会出现"扣了库存但消息丢了"的不一致。设计 **Outbox 本地消息表模式** 将分布式问题降维为单机事务问题：

```
业务事务 ──▶ 写订单 + 写 outbox_event(PENDING)   [同一 DB 事务，原子]
                              │
                OutboxRelayService ──▶ 发 RabbitMQ
                              │ 成功(Confirm ack) → markAsSent(SENT)
                              │ 失败/超时         → 留在 PENDING
   @Scheduled(30s) 扫描 PENDING ──▶ 补发（指数退避 min(10s·2ⁿ, 600s)）
                              │
                消费端：唯一索引幂等兜底 ── 失败 ──▶ DLX 三级延迟队列(10s/30s/120s) 重试
                                                  └─ 超 3 次 ──▶ 死信队列告警 + 定时对账补偿
```

- **不丢**：消息先落 `outbox_event` 本地表再发 MQ；`MqConfig` 启用 **Publisher Confirm**，broker ack 后标记 `SENT`；未确认的由定时任务补发。
- **不重**：正常路径每条消息只发一次；极端延迟场景至多重发一次，由消费端 `(user_id, voucher_id)` 唯一索引 + `outbox.message_id` 唯一索引双重兜底。
- **可补偿**：`StockReconciliationJob` 每 5 分钟对账 Redis 与 DB 库存，差异 < 10 自动修正、≥ 10 仅告警，并清理 Redis 有但 DB 无的"幽灵"下单记录，保证最终一致。

### 4. AI 智能客服（Spring AI + Function Calling）

- 基于 **Spring AI** 集成大模型（兼容 OpenAI / DashScope 协议），提供独立聊天界面，登录后可用；
- **SSE 流式输出**，逐字展示；**超时熔断 + 异常降级**，避免 AI 接口异常拖垮主链路；
- **对话记忆 MySQL 持久化**（`MysqlChatMemory`，保留最近 50 条上下文，重启不丢失）；
- 通过 **Function Calling** 注入 4 个业务工具，AI 自主理解意图并调用：`ShopTools` / `VoucherTools` / `SeckillTools` / `BlogTools`；System Prompt 内置 Prompt 注入防护 + 会话归属校验防越权。

## 💡 项目亮点

- **多级缓存闭环**：Caffeine + RBloomFilter + Redis（逻辑过期异步重建 + SETNX 互斥锁）+ 随机 TTL，完整覆盖穿透 / 击穿 / 雪崩；
- **高并发秒杀**：Redis + Lua 原子扣减（无锁竞争）+ RRateLimiter 令牌桶限流 + 数据库乐观锁 / 唯一索引兜底，压测零超卖；
- **可靠消息最终一致性**：Outbox 本地消息表 + Publisher Confirm + 死信队列三级重试 + 消费幂等 + 定时对账补偿，保证扣库存与落库最终一致、消息不丢不重；
- **AI 业务深度融合**：Spring AI + Function Calling，自然语言驱动业务查询，SSE 流式 + 熔断降级保障主链路稳定；
- **安全纵深防御**：双拦截器鉴权 + BCrypt 加密 + 明文密码无感升级 + 内容安全过滤（XSS / 敏感词）+ 会话权限校验。

## 📦 部署说明

单体 Spring Boot 架构，依赖环境：**JDK 17+**、**MySQL 8.0+**、**Redis 6.0+**、**RabbitMQ 3.0+**。

1. 执行 `llsp.sql` 初始化表结构与测试数据（含 `outbox_event` 等可靠性相关表）；
2. 参考 `src/main/resources/application-example.yaml` 编写 `application.yaml`，配置 MySQL / Redis / RabbitMQ / 大模型 API Key；
3. Maven 打包：`mvn clean package -DskipTests`；
4. 启动：`java -jar target/*.jar`（默认端口 **8081**）；
5. （可选）Nginx 反向代理配置见 `nginx-1.18.0/conf/nginx.conf`。

> 注：配置文件未上传仓库，需自行补充 `application.yaml`；AI 客服严格依赖登录态；切换大模型修改 `spring.ai.*` 相关配置即可（兼容 OpenAI 协议）。
