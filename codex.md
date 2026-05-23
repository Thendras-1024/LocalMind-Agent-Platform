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

## 用户端后端功能补齐 | 2026-05-22 20:00 | 补全前端预留用户接口闭环
### Affected Modules
localmind-core-service: `UserController`, `ShopController`, `FollowController`, `BlogCommentsController`, `UploadController`
localmind-core-service: `IUserService`, `IShopService`, `IFollowService`, `IBlogCommentsService`
localmind-core-service: `UserServiceImpl`, `ShopServiceImpl`, `FollowServiceImpl`, `BlogCommentsServiceImpl`

### Change Overview
补齐当前用户端前端已预留或页面可直接使用的后端能力，包括退出登录、附近商铺查询、关注列表、粉丝列表、评论发布、评论分页查询，以及博客图片删除接口的 DELETE 方法兼容。

### Core Modification Points
1. `/user/logout` 删除 Redis 登录 token，并清理当前线程用户上下文。
2. 新增 `/shop/of/nearby`，复用 Redis GEO 按类型、坐标和半径检索附近商铺，支持半径米值或公里值。
3. 新增 `/follow/{id}` 查询用户关注列表，新增 `/follow/fans/{id}` 查询用户粉丝列表。
4. 新增 `/blog-comments/of/blog` 按博客分页查询正常评论。
5. 新增 `POST /blog-comments` 发布评论，自动设置登录用户、默认父评论和回复评论，并递增博客评论数。
6. `GET /upload/blog/delete` 保留，新增 `DELETE /upload/blog/delete` 兼容前端删除图片请求。

### Influence Scope
本次仅补用户端后端接口和服务逻辑，不修改数据库结构、Redis/Kafka/MySQL连接配置、服务端口和分片规则。评论功能依赖已有广播表 `tb_blog_comments`，关注/粉丝列表依赖已有 `tb_follow`。

### Verification Points
1. `mvn -pl localmind-core-service -am -DskipTests compile` 编译通过。
2. 登录后调用 `/user/logout`，Redis 中对应 `login:token:{token}` 被删除。
3. 调用 `/shop/of/nearby?typeId=1&x=120.149993&y=30.334229&radius=5000` 能返回附近商铺列表。
4. 调用 `/follow/{id}` 和 `/follow/fans/{id}` 能返回用户列表。
5. 登录后 `POST /blog-comments` 可发布评论，`/blog-comments/of/blog` 可查询评论列表。
## 帖子图片上传与历史外链图片本地化 | 2026-05-22 20:30 | 统一用户端图片访问路径
### Affected Modules
localmind-core-service: `UploadController`, `StaticResourceConfig`, `application.yml`, 默认图片静态资源  
localmind-agent-web: `BlogEdit.vue`, `vite.config.js`  
scripts: `scripts/migrate_remote_images.py`  
配置：`.gitignore`

### Change Overview
修复帖子发布页上传图片后无法预览、发布后无法访问的问题。统一上传图片返回 `/imgs/...` 相对路径，后端提供 `/imgs/**` 静态资源映射，前端通过统一资源解析函数展示图片。同时新增手动执行的历史网络图片本地化迁移脚本，用于将 `tb_shop.images`、`tb_blog.images` 中的外链图片下载到本地并替换为 `/imgs/...`。

### Core Modification Points
1. 上传文件默认保存到 `./data/uploads/imgs/`，配置项为 `localmind.image-upload-dir`。
2. `POST /upload/blog` 校验图片格式，仅允许 `jpg/jpeg/png/webp/gif`，保存后返回 `/imgs/blogs/{d1}/{d2}/{uuid}.{suffix}`。
3. `/imgs/**` 同时映射本地上传目录和 classpath 默认占位图目录。
4. `DELETE /upload/blog/delete` 按 `/imgs/...` 转换本地路径，并限制只能删除上传根目录下文件。
5. 发帖页上传成功后直接保存后端返回路径，预览使用 `resolveAssetPath`，不再拼接 `/src/assets`。
6. Vite 增加 `/imgs` 代理到后端，开发环境可直接访问 `http://localhost:5173/imgs/...`。
7. 新增 `scripts/migrate_remote_images.py`，默认 dry-run，传 `--execute` 才会下载远程图片、生成回滚 SQL 并更新数据库。
8. `.gitignore` 增加 `data/uploads/`，避免本地上传文件进入版本库。

### Influence Scope
不涉及 Windows 到 Ubuntu 迁移，不修改数据库表结构，不修改 Redis/Kafka/MySQL 连接配置。历史外链迁移脚本默认不会自动执行，需要开发者手动运行。已上传图片属于运行期数据，不进入 Git。

### Verification Points
1. 后端执行 `mvn -pl localmind-core-service -am -DskipTests compile`。
2. 前端执行 `npm run build`。
3. 启动后端和前端后，在发帖页上传图片，预览应立即显示。
4. 浏览器访问 `/imgs/blogs/...` 应能看到上传图片。
5. 执行 `python scripts/migrate_remote_images.py --database hmdp_0` 应只输出统计、不修改数据库。
6. 确认无误后执行 `python scripts/migrate_remote_images.py --database hmdp_0 --execute` 进行历史图片本地化。
## Agent Recommendation Workflow Refactor | 2026-05-22 22:35 | Align current implementation with 方案.md
### Affected Modules
localmind-core-service: `org.javaup.agent.service.impl.RecommendationAgentServiceImpl`, `RecommendationContextBuilder`, `RecommendationPromptFactory`, `RecommendationCriteria`, `RecommendationCriteriaVo`, `RecommendationShopVo`, `LlmRecommendationCandidate`, `LocalMindAiProperties`, `application.yml`

### Change Overview
Refactored the recommendation Agent workflow to use Redis GEO first for type and radius candidate recall, MySQL batch detail recall, Java-side budget/open-hours filtering, composite hot score calculation, trimmed LLM ranking input, and Redis short-term criteria memory.

### Core Modification Points
1. Added session criteria memory key `agent:recommendation:session:{sessionId}` for multi-turn criteria merge.
2. Changed candidate recall from type-wide MySQL scan plus Java distance calculation to Redis GEOSEARCH with max 200 shop IDs, followed by MySQL `id IN` detail recall.
3. Added Java secondary filtering for budget and open hours before LLM ranking.
4. Added `distanceLevel`, `budgetPreference`, `compositeScore`, and `rankScore` fields.
5. Added composite score and rank score calculation based on score, sold, distance, and budget fit.
6. Trimmed LLM ranking input to required fields only: shopId, typeName, compositeScore, rankScore, avgPrice, distance, and estimated total price.
7. Removed current-chain voucher/history-consumption candidate inputs to match the current方案.md scope.
8. Changed default recommendation size to 3 and default sort to `compositeScore`.

### Influence Scope
The `/agent/recommendation/chat` API keeps the same path and response structure but candidate retrieval behavior now depends on Redis GEO keys `shop:geo:{typeId}` and browser/mock coordinates. If a shop type has no Redis GEO data, recommendation returns no candidates until GEO data is loaded.

### Verification Points
1. `mvn -pl localmind-core-service -am -DskipTests compile`
2. Type missing query should return clarification instead of defaulting to KTV.
3. Valid type and location query should go through GEOSEARCH, MySQL batch recall, Java filter, and LLM/rule ranking.
4. Follow-up query with same sessionId should merge with stored criteria and re-run the downstream recall flow.
## Agent Spring AI LangGraph4j Workflow Refactor | 2026-05-22 23:39 | Replace raw LLM client and add graph-based recommendation workflow
### Affected Modules
Parent Maven: `pom.xml`
localmind-core-service: `pom.xml`, `application.yml`
localmind-core-service: `org.javaup.agent.llm.SpringAiRecommendationLlmClient`, removed `AliyunOpenAiLlmClient`
localmind-core-service: `org.javaup.agent.service.impl.RecommendationAgentServiceImpl`

### Change Overview
Replaced the handwritten DashScope HTTP LLM client with Spring AI OpenAI-compatible integration. Added LangGraph4j as the recommendation Agent workflow orchestration layer while keeping the existing `/agent/recommendation/chat` API unchanged.

### Core Modification Points
1. Added Spring AI BOM and `spring-ai-starter-model-openai`.
2. Added `org.bsc.langgraph4j:langgraph4j-core`.
3. Configured Spring AI OpenAI-compatible DashScope access through `${DASHSCOPE_API_KEY:}` and `${AI_MODEL:qwen3.6-plus}`.
4. Removed the raw `java.net.http.HttpClient` LLM implementation.
5. Added `SpringAiRecommendationLlmClient` for chat and embedding calls.
6. Added LangGraph4j nodes for session memory, rule parse, input guard, LLM gate, intent extraction, criteria merge, required field check, candidate recall, context build, LLM ranking, and response build.
7. Switched structured output parsing to prefer Spring AI `BeanOutputConverter`, with existing JSON parser as fallback.

### Influence Scope
The recommendation API contract remains unchanged. Runtime now depends on Spring AI auto-configuration and the `DASHSCOPE_API_KEY` environment variable. The old raw HTTP LLM client is no longer available. Redis short-term criteria memory, Redis GEO recall, MySQL batch shop lookup, Java secondary filtering, LLM ranking, shopId verification, and rule fallback remain in place.

### Verification Points
1. `mvn -pl localmind-core-service -am -DskipTests compile`
2. Start backend with `DASHSCOPE_API_KEY` configured and call `/agent/recommendation/chat`.
3. Verify missing shop type still triggers clarification.
4. Verify successful query still returns at most three verified shop recommendations.
5. Verify LLM/network failure falls back to rule recommendation flow.
## Spring AI Official Config Migration | 2026-05-23 00:00 | Prefer spring.ai configuration for LLM wiring
### Affected Modules
localmind-core-service/src/main/resources/application.yml
localmind-core-service/src/main/java/org/javaup/agent/config/LocalMindAiProperties.java
localmind-core-service/src/main/java/org/javaup/agent/config/SpringAiClientConfiguration.java
localmind-core-service/src/main/java/org/javaup/agent/llm/SpringAiRecommendationLlmClient.java

### Change Overview
Move model connection settings to `spring.ai.openai.*`, remove the custom `localmind.ai` prefix from application configuration, and keep recommendation business parameters under `localmind.recommendation`.

### Core Modification Points
- Switched Spring AI bean creation to read only official `spring.ai.openai` properties.
- Renamed the custom recommendation property prefix from `localmind.ai` to `localmind.recommendation`.
- Kept legacy recommendation limits, fallback switches, and circuit/rate settings under the new custom prefix.
- Updated LLM temperature handling to use float-based values.

### Influence Scope
Impacts Spring AI bean wiring and all configuration binding for the recommendation agent. Existing recommendation workflow logic remains unchanged.

### Verification Points
- Application YAML parses correctly.
- Spring AI beans resolve from `spring.ai.openai.*`.
- Recommendation agent configuration still binds from `localmind.recommendation.*`.
- LLM temperature no longer defaults to 1 due to custom config wiring.
## LangGraph4j State Serialization Fix | 2026-05-23 11:33 | Make recommendation graph state serializable
### Affected Modules
localmind-core-service/src/main/java/org/javaup/agent/model/RecommendationCriteria.java
localmind-core-service/src/main/java/org/javaup/agent/model/LlmIntentOutput.java
localmind-core-service/src/main/java/org/javaup/agent/model/LlmRecommendationCandidate.java
localmind-core-service/src/main/java/org/javaup/agent/model/LlmRecommendationContext.java
localmind-core-service/src/main/java/org/javaup/agent/model/LlmRecommendationOutput.java
localmind-core-service/src/main/java/org/javaup/agent/vo/RecommendationCriteriaVo.java
localmind-core-service/src/main/java/org/javaup/agent/vo/RecommendationShopVo.java
localmind-core-service/src/main/java/org/javaup/agent/vo/RecommendationChatResponse.java

### Change Overview
Fix `NotSerializableException` thrown by LangGraph4j recommendation workflow by making all state-carrying DTOs serializable.

### Core Modification Points
- Added `Serializable` to recommendation criteria, LLM intent output, candidate, context, output, and response DTOs.
- Added `Serializable` to shop and criteria view objects used inside graph state.
- Kept graph structure and Spring AI call chain unchanged.

### Influence Scope
Impacts LangGraph4j state copying / persistence and response objects returned from the agent workflow. No change to recommendation scoring or LLM prompts.

### Verification Points
- `mvn -pl localmind-core-service -am -DskipTests compile` passes.
- Recommendation agent no longer throws `java.io.NotSerializableException: RecommendationCriteria`.
- Graph nodes can carry criteria and candidate payloads through the workflow.
## Spring AI Configuration Cleanup | 2026-05-23 12:01 | Remove custom LLM configuration prefix
### Affected Modules
localmind-core-service/src/main/resources/application.yml
localmind-core-service/src/main/java/org/javaup/agent/config/RecommendationAgentProperties.java
localmind-core-service/src/main/java/org/javaup/agent/config/SpringAiClientConfiguration.java
localmind-core-service/src/main/java/org/javaup/agent/llm/SpringAiRecommendationLlmClient.java
localmind-core-service/src/main/java/org/javaup/agent/llm/RecommendationLlmCircuitBreaker.java
localmind-core-service/src/main/java/org/javaup/agent/service/RecommendationContextBuilder.java
localmind-core-service/src/main/java/org/javaup/agent/service/impl/RecommendationAgentServiceImpl.java

### Change Overview
Removed the custom `localmind.ai` LLM configuration path. LLM connection settings now come from Spring AI official `spring.ai.openai.*` properties, while recommendation business switches and limits are bound from `localmind.recommendation.*`.

### Core Modification Points
- Rebuilt `application.yml` to keep a single Spring AI OpenAI-compatible DashScope configuration.
- Replaced `LocalMindAiProperties` with `RecommendationAgentProperties` for recommendation-only settings.
- Deleted handwritten Spring AI bean wiring and switched to Spring AI starter auto-configuration.
- Updated the recommendation LLM client to check `spring.ai.openai.api-key` and read model options from Spring AI properties.
- Removed per-call model, temperature, and token defaults from recommendation service code.

### Influence Scope
Impacts recommendation Agent LLM configuration and startup bean wiring. The recommendation API contract remains unchanged. Runtime now requires `DASHSCOPE_API_KEY` for LLM calls; without it, the existing fallback path remains available.

### Verification Points
- `mvn -pl localmind-core-service -am -DskipTests compile` passes.
- `localmind.ai` no longer appears in backend source or configuration.
- Spring AI auto-configuration provides the required chat and embedding beans.
## Recommendation Agent Legacy Cleanup | 2026-05-23 13:23 | Keep LLM-first workflow and gate rule matching
### Affected Modules
localmind-core-service/src/main/resources/application.yml
localmind-core-service/src/main/java/org/javaup/agent/config/RecommendationAgentProperties.java
localmind-core-service/src/main/java/org/javaup/agent/service/impl/RecommendationAgentServiceImpl.java
localmind-core-service/src/main/java/org/javaup/agent/llm/LlmChatOptions.java
localmind-core-service/src/main/java/org/javaup/agent/llm/RecommendationLlmClient.java
localmind-core-service/src/main/java/org/javaup/agent/llm/SpringAiRecommendationLlmClient.java

### Change Overview
Removed redundant recommendation Agent legacy flow and unused LLM options. Added a configuration switch to disable rule-based query parsing by default so user query understanding is handled by the LLM path during ongoing Agent development.

### Core Modification Points
- Added `localmind.recommendation.rule-matching-enabled`, defaulting to `false`.
- Gated `RecommendationIntentParser` usage behind the new rule matching switch.
- Simplified the recommendation API entrypoint to use the LangGraph4j workflow directly.
- Removed the duplicated legacy service flow outside the graph.
- Removed unused `enableThinking` and unused embedding method/config from the recommendation LLM abstraction.

### Influence Scope
Impacts only recommendation Agent query parsing and LLM abstraction cleanup. Existing graph-level LLM failure handling and rule response fallback remain available. When rule matching is disabled and LLM is unavailable, fallback no longer attempts regex-based natural language understanding.

### Verification Points
- `mvn -pl localmind-core-service -am -DskipTests compile` passes.
- No custom HTTP LLM client or manual OpenAI model wiring remains in backend source.
- No `localmind.ai` configuration remains in active backend source or application configuration.
## Redis GEO Radius Compatibility Fix | 2026-05-23 14:16 | Support current Redis 5 GEO command set
### Affected Modules
localmind-core-service/src/main/java/org/javaup/service/impl/ShopServiceImpl.java
localmind-core-service/src/main/java/org/javaup/agent/service/impl/RecommendationAgentServiceImpl.java

### Change Overview
Replaced runtime nearby-shop Redis GEO lookup usage with Redis 5 compatible `GEORADIUS` semantics. Cleaned up Agent internal naming so code no longer exposes `GEOSEARCH` terminology for the compatible radius lookup path.

### Core Modification Points
- Changed `ShopServiceImpl` nearby shop lookup from `opsForGeo().search(...)` / `GeoSearchCommandArgs` to `opsForGeo().radius(...)` / `GeoRadiusCommandArgs`.
- Added `Circle` and `Metrics.KILOMETERS` based radius query construction.
- Kept distance inclusion, ascending sort, and page upper-bound limit behavior unchanged.
- Renamed recommendation Agent internal GEO recall helper from `GeoSearchResult` / `geoSearchShopIds` to `GeoRadiusResult` / `geoRadiusShopIds`.

### Influence Scope
Impacts nearby shop query and Agent candidate GEO recall compatibility with the current Redis version. API response structure, pagination logic, distance sorting, and MySQL detail recall behavior remain unchanged.

### Verification Points
- Runtime source no longer contains `GeoSearchCommandArgs`, `GeoReference.fromCoordinate`, or `opsForGeo().search`.
- `mvn -pl localmind-core-service -am -DskipTests compile` passes.
## Legacy Utility Cleanup | 2026-05-23 14:21 | Remove unused development-only code
### Affected Modules
localmind-core-service/src/main/java/org/javaup/controller/TestController.java
localmind-core-service/src/main/java/org/javaup/utils/PasswordEncoder.java
localmind-core-service/src/main/java/org/javaup/utils/ILock.java
localmind-core-service/src/main/java/org/javaup/utils/SimpleRedisLock.java
localmind-core-service/src/main/resources/unlock.lua
localmind-core-service/src/main/java/org/javaup/service/impl/VoucherOrderServiceImpl.java

### Change Overview
Removed unused development-only test endpoint, unused password utility, and legacy handwritten Redis lock implementation. Cleaned up the remaining commented reference in voucher order handling.

### Core Modification Points
- Deleted `/test/meter` test controller.
- Deleted unused MD5 password encoder utility.
- Deleted old `ILock` / `SimpleRedisLock` Redis lock abstraction and its Lua unlock script.
- Removed the obsolete commented `SimpleRedisLock` reference from `VoucherOrderServiceImpl`; Redisson lock logic remains unchanged.

### Influence Scope
No active business API or service dependency is removed. Current login, voucher order, Redisson lock, and recommendation flows remain unchanged.

### Verification Points
- Static scan leaves no active `TestController`, `PasswordEncoder`, `ILock`, `SimpleRedisLock`, or `unlock.lua` references.
- `mvn -pl localmind-core-service -am -DskipTests compile` passes.
## Recommendation Agent Minimal Workflow Fix | 2026-05-23 15:20 | Make LangGraph4j LLM workflow runnable
### Affected Modules
localmind-core-service/src/main/java/org/javaup/agent/service/impl/RecommendationAgentServiceImpl.java
localmind-core-service/src/main/java/org/javaup/agent/llm/RecommendationPromptFactory.java

### Change Overview
Fixed LangGraph4j state access that caused node execution to fail before LLM invocation. Simplified the recommendation graph so the primary path uses LLM intent extraction instead of rule parsing, matching the planned Agent workflow.

### Core Modification Points
- Replaced unsafe `state.value(key, null/default)` usage with Optional-based safe state reads.
- Removed the `rule_parse` node from the main graph path.
- Kept session memory, input guard, LLM gate, intent extraction, criteria merge, required-field check, candidate recall, context build, LLM ranking, and response build nodes.
- Updated intent prompt constraints to 3km/5km/10km radius normalization and `compositeScore` sorting.
- Extended intent JSON schema to include `budgetPreference` and `distanceLevel`.

### Influence Scope
Impacts recommendation Agent workflow execution. The main path now attempts LLM parsing first and should no longer fall back because of missing LangGraph4j state fields. Existing fallback still remains for LLM unavailability or graph-level exceptions.

### Verification Points
- No active `state.value(key, default)` calls remain in `RecommendationAgentServiceImpl`.
- No `rule_parse` node remains in the recommendation graph.
- `mvn -pl localmind-core-service -am -DskipTests compile` passes.
- `mvn -pl localmind-core-service -Dtest=RecommendationContextBuilderTest test` passes.
## Spring AI Auto-Configuration LLM Client Cleanup | 2026-05-23 15:40 | Delegate model options to Spring AI
### Affected Modules
localmind-core-service/src/main/java/org/javaup/agent/llm/SpringAiRecommendationLlmClient.java
localmind-core-service/src/main/java/org/javaup/agent/llm/LlmChatOptions.java

### Change Overview
Removed manual reading and forwarding of Spring AI OpenAI model options from the recommendation LLM client. The client now relies on Spring AI auto-configuration to bind `application.yml` properties such as model, temperature, max tokens, base URL, and API key.

### Core Modification Points
- Removed custom `@Value` reads for `spring.ai.openai.api-key`, model, temperature, and max tokens.
- Removed manual `.model(...)`, `.temperature(...)`, and `.maxTokens(...)` option forwarding.
- Kept only per-request `responseFormat` construction because structured output mode changes between calls.
- Simplified `LlmChatOptions` to carry only dynamic response format data.
- Reads response model name from Spring AI `ChatResponseMetadata` when available.

### Influence Scope
Impacts recommendation Agent LLM invocation configuration. Spring AI now fully owns static provider/model option binding from `application.yml`; business code only controls JSON response format per request.

### Verification Points
- No custom Spring AI `@Value` reads remain in recommendation Agent source.
- No manual temperature/max-token forwarding remains in the recommendation LLM client.
- `mvn -pl localmind-core-service -am -DskipTests compile` passes.
