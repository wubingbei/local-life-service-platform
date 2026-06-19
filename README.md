# 本地生活服务平台 | 高并发秒杀 + AI 智能助手

本项目是一款类似于大众点评的本地生活服务平台，基于 **Spring Boot 3 + JDK 17 + Redis** 构建，聚焦商户查询、优惠券秒杀、探店笔记、AI 智能客服等核心业务场景。针对高并发读写、热点缓存、秒杀超卖等经典问题做了全链路优化，并深度集成 **Spring AI + Function Calling**，实现自然语言驱动业务查询与个性化服务。

## ✨ 技术栈

- 后端：**Spring Boot 3.2**、Spring MVC、MyBatis-Plus、**Spring AI**
- 数据库：MySQL 8.0+、Redis 6.0+
- 消息队列：RabbitMQ 3.0+
- 缓存：Redis + Caffeine（二级缓存）
- 分布式锁：Redisson + Lua 脚本
- 安全：BCrypt + 内容安全过滤（XSS / 敏感词）

## 📌 项目背景

针对本地生活服务场景中**高并发秒杀、热点数据查询、用户咨询效率低**三大痛点设计：

1. **秒杀场景**：限时优惠券抢购存在超卖、数据库压力激增、接口阻塞等问题，需要从 Redis 原子操作 → 消息队列削峰 → 数据库兜底多层面解决；
2. **热点查询**：商户详情是高频读场景，需构建多级缓存体系，覆盖穿透、击穿、雪崩等边界情况；
3. **咨询效率**：传统客服依赖固定菜单操作，用户学习成本高，引入 AI 智能客服，支持自然语言直接查询商户、优惠券、笔记，实现业务系统与大模型无缝联动。

## 🚀 核心功能

### 1. 用户登录

- 手机号 + 验证码 / 密码双模式登录，验证码 60 秒发送限流，Redis 存储有效期；
- 双层拦截器链：`RefreshTokenInterceptor`（刷新 Token）+ `LoginInterceptor`（校验登录态），Redis 存储会话，支持分布式共享；

### 2. 商户信息缓存优化

- **Caffeine（本地）+ Redis（远程）二级缓存**，热点接口响应大幅提升；
- 解决**缓存穿透**：空值缓存，拦截不存在商户的无效请求；
- 解决**缓存击穿**：两套经典方案——
  - **互斥锁**：Redis `SETNX` 控制单线程重建，Double Check + 自旋重试，保证强一致性；
  - **逻辑过期**：缓存不删除仅标记过期，后台异步线程池重建，高并发下主线程不阻塞；
- 缓解**缓存雪崩**：TTL 添加随机偏移，避免批量 key 同时失效；
- 支持按评分、销量、价格多维度排序；写操作同步删除两级缓存保证一致性。

### 3. 高并发优惠券秒杀系统

- **Lua 脚本原子化**：库存校验 + 一人一单判断 + 库存扣减 + 用户记录，四步在 Redis 单线程中一次完成；
- **RabbitMQ 异步削峰**：Lua 校验通过即发消息到队列并返回，订单入库由 `SeckillListener` 异步消费，不阻塞用户；
- **Redisson 分布式锁**：消费端按用户 ID 加锁，防止同一用户并发重复下单；
- **数据库双层兜底**：乐观锁（`WHERE stock > 0`）防超卖 + 唯一索引防重复；
- **全局唯一订单 ID**：时间戳（32位）+ Redis 自增序列号（32位），按天重置，适合分布式环境。

### 4. AI 智能客服（Spring AI + Function Calling）

- 基于 **Spring AI** 集成大模型（通义千问 DashScope 兼容接口），提供独立聊天界面，登录后可用；
- **SSE 流式输出**，逐字展示；**10 秒超时熔断**，超时自动降级并持久化错误提示；
- **对话记忆 MySQL 持久化** — 自研 `MysqlChatMemory`，保留最近 50 条上下文，突破 Redis 内存限制，服务重启不丢失；
- 多会话管理：创建 / 切换 / 删除会话，标题自动生成（首条消息前 30 字），上下文上限拦截提示；
- 通过 **Function Calling** 注入 4 个业务工具，AI 自主理解意图并调用：
  - `ShopTools` — 按类型查询商户、按评分 / 销量 / 价格排序
  - `VoucherTools` — 查询优惠券列表与规则
  - `SeckillTools` — 秒杀抢券
  - `BlogTools` — 探店笔记推荐
- 会话归属校验，防止越权；System Prompt 内置 Prompt 注入防护。

## 💡 项目亮点

- **技术栈现代化**：Spring Boot 3.2 + JDK 17 + Spring AI，兼容企业新一代技术栈；
- **高并发实战**：独立设计秒杀全链路方案（Lua → MQ → Redisson → 乐观锁 + 唯一索引），解决超卖与重复下单；
- **多级缓存闭环**：Caffeine + Redis 二级缓存，完整覆盖穿透、击穿（互斥锁 / 逻辑过期）、雪崩，具备生产级缓存设计能力；
- **AI 业务深度融合**：Spring AI + Function Calling，自然语言驱动业务查询，AI 与业务系统无缝联动；
- **安全纵深防御**：双拦截器鉴权 + BCrypt 加密 + 明文密码无感升级 + 内容安全过滤（XSS / 敏感词）+ 会话权限校验；
- **代码规范**：分层清晰、工具类封装完善，具备生产级可读性。

## 📦 部署说明

本项目采用 Spring Boot 单体架构，依赖环境：

- JDK 17+（强制）
- MySQL 8.0+
- Redis 6.0+
- RabbitMQ 3.0+

### 部署步骤

1. 创建数据库，执行 `llsp.sql` 初始化表结构与测试数据；
2. 参考 `src/main/resources/application-example.yaml` 编写 `application.yaml`，配置 MySQL、Redis、RabbitMQ、大模型 API Key；
3. Maven 打包：`mvn clean package -DskipTests`；
4. 启动：`java -jar target/*.jar`（默认端口 **8081**）；
5. （可选）配置 Nginx 反向代理，配置文件见 `nginx-1.18.0/conf/nginx.conf`。

### ⚠️ 注意事项

- 配置文件未上传至仓库，需自行补充 `application.yaml`；
- Spring AI 需配置有效大模型密钥（DashScope API Key）；
- AI 客服模块严格依赖登录状态，未登录无法访问接口与页面；
- 如需切换大模型，修改 `application.yaml` 中 `spring.ai.openai` 相关配置即可，兼容 OpenAI 接口协议。
