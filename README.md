# 智邻生活 Agent 平台

英文名：LocalMind Agent Platform

智邻生活 Agent 平台是一个面向本地生活场景的 Java 后端项目。项目围绕商户、用户、优惠券、订单、附近门店、达人探店、关注关系、签到统计、高并发秒杀、缓存、消息队列和分库分表等业务能力展开，并计划进一步扩展智能推荐导购 Agent、探店出行规划 Agent 等智能化模块。

## 项目定位

项目不只是传统的店铺浏览或优惠券系统，而是希望在现有本地生活业务基础上，引入 Agent 能力，让系统能够理解用户的自然语言需求，并结合位置、历史消费、门店信息、评分、价格、优惠券和营业状态等数据完成推荐与决策辅助。

## 计划中的 Agent 能力

- 智能推荐导购 Agent：面向用户端首页，支持用户用自然语言描述消费需求，系统根据类型、时间、距离、预算、评分、历史消费等条件推荐门店。
- 探店出行规划 Agent：结合用户位置、偏好和优惠券可用门店，规划周边美食、休闲、娱乐探店路线。
- 门店风险提醒：在数据支持的情况下，提醒营业状态、排队热度、优惠券截止时间等信息。

## 技术栈

- Java 17
- Spring Boot 3
- MyBatis-Plus
- Redis / Redisson
- Kafka
- ShardingSphere
- Vue 3
- Vite

## 模块说明

当前 Maven 子模块目录已统一使用 `localmind` 命名前缀：

- `localmind-common`：公共常量、枚举、异常和工具类
- `localmind-core-service`：核心业务服务
- `localmind-sharding`：分库分表支持
- `localmind-redisson-framework`：分布式锁、布隆过滤器、重复执行限制、延迟队列
- `localmind-redis-tool-framework`：Redis 缓存和限流工具
- `localmind-id-generator-framework`：分布式 ID 生成
- `localmind-mq-framework`：消息队列生产者和消费者封装
- `localmind-parameter`：业务参数对象

## 启动说明

后端主服务位于 `localmind-core-service`，前端应用位于 `localmind-agent-web`。

```sh
mvn -pl localmind-core-service -am package -DskipTests
```

```sh
cd localmind-agent-web
pnpm install
pnpm dev
```
