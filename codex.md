## 新增用户端智能推荐导购Agent 2026-05-18 17:25 

### 1. 涉及模块范围
后端：`org.javaup.agent` 推荐导购Agent模块、`ShopServiceImpl` 门店距离查询逻辑  
前端：用户端首页、推荐导购对话页、Agent接口封装、首页样式  
缓存：Redis 推荐导购会话缓存，TTL 1小时

### 2. 改动简介
新增面向用户端的智能推荐导购Agent第一版能力，支持用户通过自然语言描述人数、品类、距离、预算、时间等需求，由后端解析意图并筛选门店，结合历史券订单识别用户曾消费过的门店，前端提供首页推荐小精灵入口、对话页面与推荐卡片跳转能力。

### 3. 核心变更点
1. 新增推荐导购Agent后端接口 `/agent/recommendation/chat`，统一接收用户消息、会话ID与浏览器定位坐标
2. 新增规则解析器，支持识别KTV/美食、人数、距离、总预算、时间段、评分优先等基础推荐条件
3. 新增推荐服务实现，基于门店类型、坐标距离、人均价格估算、总预算、评分/距离/价格排序生成推荐结果
4. 接入用户历史券订单数据，标记曾消费过的门店，并在推荐回复中提示用户可优先考虑
5. 新增Redis会话缓存，按用户与会话维度保存最近一次导购上下文，缓存有效期1小时
6. 修复门店类型查询中忽略前端坐标的问题，使距离筛选与Redis GEO查询逻辑可按传入坐标生效
7. 新增前端 `agent.js` API封装、`/recommendAgent` 路由、推荐导购对话页面与首页悬浮推荐小精灵入口

### 4. 影响范围
新增独立Agent接口与用户端页面，不破坏原有门店、优惠券、订单接口协议。  
会读取门店、优惠券、券订单数据，并新增Redis键 `agent:recommendation:chat:{userId}:{sessionId}`。  
`ShopServiceImpl` 坐标逻辑修正会影响原有按类型查询门店接口在传入坐标时的距离排序/筛选表现。  
前端新增首页入口与导购页，需要与后端登录态、定位权限、图片资源路径联调。

### 5. 测试验证点
1. 后端执行 `mvn -pl localmind-core-service -am -DskipTests compile` 编译通过
2. 登录用户调用 `/agent/recommendation/chat` 能正常返回会话ID、回复文案、推荐条件与推荐门店列表
3. 输入“我和父母今天下午5点到8点想找5公里内评分高、总价150元以下的KTV”能解析人数、KTV、5公里、150元预算与时间段
4. 浏览器允许定位后，推荐结果按距离、预算、评分条件筛选；拒绝定位时能给出补充定位提示
5. 有历史券订单的用户，符合条件的历史消费门店能被标记并在推荐理由中说明
6. 首页推荐小精灵可跳转导购页，导购页推荐卡片可跳转门店详情
7. 前端构建需本地补充验证；当前Node/NPM命令在执行器中因进程创建失败未完成构建，不属于前端代码编译报错

## 用户端智能推荐导购Agent接入LLM API | 2026-05-18 19:25 | 接入阿里云OpenAI格式大模型
### Affected Modules
后端：`localmind-core-service` 的 `org.javaup.agent` Agent模块、LLM客户端封装、推荐上下文构建、推荐服务实现  
配置：`localmind-core-service/src/main/resources/application.yml` 新增 `localmind.ai` 配置段  
测试：新增 LLM JSON 结构化解析与候选上下文裁剪单元测试

### Change Overview
将用户端推荐导购Agent从纯字符匹配实现改造为 LLM-first 推荐流程：先由大模型抽取结构化意图，再由后端按意图读取门店、优惠券、历史消费等候选数据，裁剪为受控上下文交给大模型筛选推荐。保留原 `/agent/recommendation/chat` 接口和响应结构，LLM不可用、超时、限流、熔断或输出非法时自动降级到原规则推荐逻辑。

### Core Modification Points
1. 新增阿里云百炼 OpenAI 兼容客户端，支持 `/chat/completions` 与 `/embeddings`，API Key 使用 `${DASHSCOPE_API_KEY:}` 环境变量占位
2. 新增 `localmind.ai` 配置，包含 chat/embedding 模型、超时、最大候选数、上下文字符上限、JSON Schema开关、Redis熔断和用户级限流参数
3. 新增 LLM 结构化输出解析、JSON Schema/JSON Mode响应格式、非法JSON修复重试、候选门店上下文裁剪
4. 推荐主流程改为：输入安全检查 -> LLM意图抽取 -> 后端候选数据检索 -> 上下文裁剪 -> LLM结构化推荐 -> 后端校验回填真实门店卡片
5. 幻觉抑制：LLM只允许返回候选 `shopId` 和推荐原因，门店名称、价格、评分、距离、优惠券等字段全部以后端数据为准
6. 熔断降级：Redis记录LLM连续失败并短时间熔断；模型未配置、超时、429/5xx、结构化失败、上下文超限均回落规则推荐

### Influence Scope
不改变用户端接口路径、请求体和响应字段，不修改Redis、Kafka、MySQL连接地址、服务端口、分片规则和现有门店/优惠券/订单接口协议。新增 Redis 键包括 `agent:recommendation:llm:circuit:*` 与 `agent:recommendation:rate:{userId}`，原会话缓存键继续使用 `agent:recommendation:chat:{userId}:{sessionId}`。

### Verification Points
1. `mvn -pl localmind-core-service -am -DskipTests compile` 编译通过
2. `LlmJsonParserTest` 验证普通JSON、Markdown fenced JSON和非法输出处理
3. `RecommendationContextBuilderTest` 验证候选数量和上下文字符上限裁剪
4. 未配置 `DASHSCOPE_API_KEY` 时 `/agent/recommendation/chat` 应自动走规则推荐降级
5. 配置真实阿里云百炼 Key 后，推荐请求应返回结构化 `reply/criteria/recommendations/needClarification/missingFields`
## Nearby Shop Coordinate Switch And Test Seed Data | 2026-05-22 15:04 | Support local-development nearby search and safe small-batch shop data import
### Affected Modules
localmind-core-service: `org.javaup.config.NearbyLocationProperties`, `org.javaup.controller.ShopController`, `org.javaup.service.impl.ShopServiceImpl`, `org.javaup.init.ShopGeoDataInit`, `application.yml`
localmind-agent-web: `src/api/shop.js`, `src/views/shop/ShopList.vue`
sql: `sql/2_test_shop_seed.sql`, `sql/2_test_shop_seed_rollback.sql`

### Change Overview
Added a configurable nearby-shop coordinate mode. When `localmind.nearby.real-coordinate-enabled=false`, nearby search uses configured mock coordinates inside the current shop coordinate range. When set to `true`, the frontend attempts browser geolocation and the backend respects submitted coordinates. Added a Redis GEO rebuild initializer so direct SQL shop imports can be reflected after backend restart.

### Core Modification Points
1. Added `localmind.nearby` configuration with `real-coordinate-enabled`, `mock-x`, and `mock-y`.
2. Exposed `/shop/location/config` so the frontend can decide whether to use browser geolocation or mock coordinates.
3. Updated `/shop/of/type` service logic to force configured mock coordinates when real-coordinate mode is disabled.
4. Added startup rebuild of `shop:geo:{typeId}` Redis GEO indexes from `tb_shop`.
5. Kept API save/update shop flows in sync with Redis GEO indexes.
6. Added small-batch test seed SQL and rollback SQL for broadcast-table insertion into both `hmdp_0` and `hmdp_1`.

### Influence Scope
Nearby shop list behavior now depends on `localmind.nearby.real-coordinate-enabled`. Development default is mock-coordinate search, avoiding meaningless browser coordinates. Direct SQL shop imports require backend restart to rebuild Redis GEO. `tb_shop` remains a broadcast table and generated seed rows must be inserted into both databases.

### Verification Points
1. `mvn -pl localmind-core-service -am -DskipTests compile`
2. Confirm `/shop/location/config` returns the configured coordinate mode.
3. With `real-coordinate-enabled=false`, `/shop/of/type` returns distance-sorted shops around the configured mock coordinate.
4. Import `sql/2_test_shop_seed.sql`, restart backend, then confirm generated shops appear in nearby search.
5. Run `sql/2_test_shop_seed_rollback.sql` to remove only the generated test ID range.
