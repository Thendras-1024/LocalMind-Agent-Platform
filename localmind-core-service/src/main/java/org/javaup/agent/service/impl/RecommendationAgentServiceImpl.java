package org.javaup.agent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.javaup.agent.config.RecommendationAgentProperties;
import org.javaup.agent.dto.RecommendationChatRequest;
import org.javaup.agent.llm.LlmChatMessage;
import org.javaup.agent.llm.LlmChatOptions;
import org.javaup.agent.llm.LlmJsonParser;
import org.javaup.agent.llm.RecommendationLlmCircuitBreaker;
import org.javaup.agent.llm.RecommendationLlmClient;
import org.javaup.agent.llm.RecommendationPromptFactory;
import org.javaup.agent.model.LlmIntentOutput;
import org.javaup.agent.model.LlmRecommendationCandidate;
import org.javaup.agent.model.LlmRecommendationContext;
import org.javaup.agent.model.LlmRecommendationOutput;
import org.javaup.agent.model.RecommendationCriteria;
import org.javaup.agent.service.IRecommendationAgentService;
import org.javaup.agent.service.RecommendationContextBuilder;
import org.javaup.agent.vo.RecommendationChatResponse;
import org.javaup.agent.vo.RecommendationCriteriaVo;
import org.javaup.agent.vo.RecommendationShopVo;
import org.javaup.entity.Shop;
import org.javaup.entity.ShopType;
import org.javaup.service.IShopService;
import org.javaup.service.IShopTypeService;
import org.javaup.utils.UserHolder;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.javaup.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Service
public class RecommendationAgentServiceImpl implements IRecommendationAgentService {

    private static final String CHAT_KEY_PREFIX = "agent:recommendation:chat:";
    private static final String SESSION_CRITERIA_KEY_PREFIX = "agent:recommendation:session:";
    private static final long CHAT_TTL_HOURS = 1L;
    private static final int DEFAULT_RECOMMENDATION_SIZE = 3;
    private static final int DEFAULT_RADIUS_METERS = 5000;
    private static final int GEO_CANDIDATE_LIMIT = 200;
    private static final Set<Integer> ALLOWED_RADIUS_METERS = Set.of(3000, 5000, 10000);
    private static final Set<String> ALLOWED_SORTS = Set.of("compositeScore", "distance", "price");

    private CompiledGraph<RecommendationWorkflowState> recommendationGraph;

    @Resource
    private RecommendationLlmClient recommendationLlmClient;

    @Resource
    private RecommendationPromptFactory recommendationPromptFactory;

    @Resource
    private LlmJsonParser llmJsonParser;

    @Resource
    private RecommendationContextBuilder recommendationContextBuilder;

    @Resource
    private RecommendationLlmCircuitBreaker recommendationLlmCircuitBreaker;

    @Resource
    private RecommendationAgentProperties aiProperties;

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void initRecommendationGraph() throws GraphStateException {
        recommendationGraph = new StateGraph<>(RecommendationWorkflowState::new)
                .addNode("session_memory", node_async(this::sessionMemoryNode))
                .addNode("input_guard", node_async(this::inputGuardNode))
                .addNode("llm_gate", node_async(this::llmGateNode))
                .addNode("intent_extract", node_async(this::intentExtractNode))
                .addNode("intent_safety", node_async(this::intentSafetyNode))
                .addNode("criteria_merge", node_async(this::criteriaMergeNode))
                .addNode("required_check", node_async(this::requiredCheckNode))
                .addNode("candidate_recall", node_async(this::candidateRecallNode))
                .addNode("context_build", node_async(this::contextBuildNode))
                .addNode("llm_rank", node_async(this::llmRankNode))
                .addNode("response_build", node_async(this::responseBuildNode))
                .addEdge(START, "session_memory")
                .addEdge("session_memory", "input_guard")
                .addConditionalEdges("input_guard", edge_async(state -> state.<String>value("route").orElse("continue")),
                        Map.of("direct_response", END, "continue", "llm_gate"))
                .addConditionalEdges("llm_gate", edge_async(state -> state.<String>value("route").orElse("continue")),
                        Map.of("direct_response", END, "continue", "intent_extract"))
                .addEdge("intent_extract", "intent_safety")
                .addConditionalEdges("intent_safety", edge_async(state -> state.<String>value("route").orElse("continue")),
                        Map.of("direct_response", END, "continue", "criteria_merge"))
                .addEdge("criteria_merge", "required_check")
                .addConditionalEdges("required_check", edge_async(state -> state.<String>value("route").orElse("continue")),
                        Map.of("direct_response", END, "continue", "candidate_recall"))
                .addConditionalEdges("candidate_recall", edge_async(state -> state.<String>value("route").orElse("continue")),
                        Map.of("direct_response", END, "continue", "context_build"))
                .addEdge("context_build", "llm_rank")
                .addEdge("llm_rank", "response_build")
                .addEdge("response_build", END)
                .compile();
    }

    @Override
    public RecommendationChatResponse chat(RecommendationChatRequest request) {
        return chatByGraph(request);
    }

    private RecommendationChatResponse chatByGraph(RecommendationChatRequest request) {
        Long userId = UserHolder.getUser().getId();
        String sessionId = normalizeSessionId(request.getSessionId());
        try {
            RecommendationWorkflowState state = recommendationGraph.invoke(new HashMap<>(Map.of(
                    "userId", userId,
                    "sessionId", sessionId,
                    "request", request
            ))).orElseThrow(() -> new IllegalStateException("recommendation graph returned empty state"));
            RecommendationChatResponse response = getStateValue(state, "response");
            if (response == null) {
                throw new IllegalStateException("recommendation graph returned empty response");
            }
            saveConversation(userId, sessionId, request, response);
            if (Boolean.TRUE.equals(getStateValue(state, "llmAttempted", false))) {
                recommendationLlmCircuitBreaker.recordSuccess();
            }
            return response;
        } catch (Exception e) {
            log.warn("Recommendation LangGraph4j flow failed, fallback to rule response, userId={}, sessionId={}, reason={}",
                    userId, sessionId, e.getMessage(), e);
            RecommendationCriteria criteria = normalizeCriteria(null, loadSessionCriteria(sessionId));
            RecommendationChatResponse response = fallbackChat(userId, sessionId, request, criteria, "graph_failed");
            saveConversation(userId, sessionId, request, response);
            return response;
        }
    }

    private Map<String, Object> sessionMemoryNode(RecommendationWorkflowState state) {
        String sessionId = getStateValue(state, "sessionId", "");
        return put("previousCriteria", loadSessionCriteria(sessionId));
    }

    private Map<String, Object> inputGuardNode(RecommendationWorkflowState state) {
        RecommendationChatRequest request = getStateValue(state, "request");
        String sessionId = getStateValue(state, "sessionId", "");
        RecommendationCriteria previousCriteria = getStateValue(state, "previousCriteria", new RecommendationCriteria());
        if (StrUtil.length(request.getMessage()) > aiProperties.getMaxMessageChars()) {
            return put("response", clarificationResponse(sessionId, previousCriteria, List.of("message"),
                    "用户输入过长，请缩短需求后再试。"), "route", "direct_response");
        }
        if (isUnsafeInput(request.getMessage())) {
            return put("response", safetyResponse(sessionId, previousCriteria), "route", "direct_response");
        }
        return put("route", "continue");
    }

    private Map<String, Object> llmGateNode(RecommendationWorkflowState state) {
        Long userId = getStateValue(state, "userId");
        String sessionId = getStateValue(state, "sessionId", "");
        RecommendationChatRequest request = getStateValue(state, "request");
        RecommendationCriteria previousCriteria = getStateValue(state, "previousCriteria", new RecommendationCriteria());
        if (!shouldUseLlm(userId)) {
            log.info("Recommendation agent skip LLM, userId={}, sessionId={}, reason=llm_gate_closed", userId, sessionId);
            return put("response", fallbackChat(userId, sessionId, request, previousCriteria, "llm_skipped"),
                    "route", "direct_response");
        }
        log.info("Recommendation agent enter LLM workflow, userId={}, sessionId={}, messageLength={}",
                userId, sessionId, request == null ? 0 : StrUtil.length(request.getMessage()));
        return put("route", "continue", "llmAttempted", true);
    }

    private Map<String, Object> intentExtractNode(RecommendationWorkflowState state) {
        RecommendationChatRequest request = getStateValue(state, "request");
        RecommendationCriteria previousCriteria = getStateValue(state, "previousCriteria", new RecommendationCriteria());
        return put("intent", extractIntent(request.getMessage(), previousCriteria));
    }

    private Map<String, Object> intentSafetyNode(RecommendationWorkflowState state) {
        String sessionId = getStateValue(state, "sessionId", "");
        RecommendationCriteria previousCriteria = getStateValue(state, "previousCriteria", new RecommendationCriteria());
        LlmIntentOutput intent = getStateValue(state, "intent");
        if (intent == null || !Boolean.TRUE.equals(intent.getSafe()) || !Boolean.TRUE.equals(intent.getLocalLifeRecommendation())) {
            String reply = intent == null ? null : intent.getSafetyReply();
            return put("response", safetyResponse(sessionId, previousCriteria, StrUtil.blankToDefault(reply,
                    "我只能处理本地生活门店推荐相关需求，请换一种门店推荐需求描述。")), "route", "direct_response");
        }
        return put("route", "continue");
    }

    private Map<String, Object> criteriaMergeNode(RecommendationWorkflowState state) {
        RecommendationCriteria previousCriteria = getStateValue(state, "previousCriteria", new RecommendationCriteria());
        LlmIntentOutput intent = getStateValue(state, "intent");
        return put("criteria", normalizeCriteria(intent == null ? null : intent.getCriteria(), previousCriteria));
    }

    private Map<String, Object> requiredCheckNode(RecommendationWorkflowState state) {
        String sessionId = getStateValue(state, "sessionId", "");
        RecommendationChatRequest request = getStateValue(state, "request");
        RecommendationCriteria criteria = getStateValue(state, "criteria", new RecommendationCriteria());
        LlmIntentOutput intent = getStateValue(state, "intent");
        List<String> missingFields = mergeMissingFields(intent == null ? null : intent.getMissingFields(),
                resolveMissingFields(request, criteria));
        if (!missingFields.isEmpty()) {
            return put("response", clarificationResponse(sessionId, criteria, missingFields,
                    buildClarificationReply(criteria, missingFields)), "route", "direct_response");
        }
        return put("missingFields", missingFields, "route", "continue");
    }

    private Map<String, Object> candidateRecallNode(RecommendationWorkflowState state) {
        Long userId = getStateValue(state, "userId");
        String sessionId = getStateValue(state, "sessionId", "");
        RecommendationChatRequest request = getStateValue(state, "request");
        RecommendationCriteria criteria = getStateValue(state, "criteria", new RecommendationCriteria());
        List<LlmRecommendationCandidate> candidates = queryCandidates(userId, request, criteria);
        log.info("Recommendation agent candidate recall finished, userId={}, sessionId={}, typeId={}, radiusMeters={}, candidateSize={}",
                userId, sessionId, criteria.getTypeId(), criteria.getRadiusMeters(), candidates.size());
        if (candidates.isEmpty()) {
            return put("response", emptyRecommendationResponse(sessionId, criteria), "route", "direct_response");
        }
        return put("candidates", candidates, "route", "continue");
    }

    private Map<String, Object> contextBuildNode(RecommendationWorkflowState state) {
        RecommendationCriteria criteria = getStateValue(state, "criteria", new RecommendationCriteria());
        List<LlmRecommendationCandidate> candidates = getStateValue(state, "candidates", new ArrayList<>());
        LlmRecommendationContext context = recommendationContextBuilder.build(candidates, criteria);
        if (context.getCandidates().isEmpty()) {
            throw new IllegalStateException("candidate context exceeds max size");
        }
        return put("context", context);
    }

    private Map<String, Object> llmRankNode(RecommendationWorkflowState state) {
        RecommendationChatRequest request = getStateValue(state, "request");
        RecommendationCriteria criteria = getStateValue(state, "criteria", new RecommendationCriteria());
        LlmRecommendationContext context = getStateValue(state, "context");
        return put("output", recommendByLlm(request.getMessage(), criteria, context));
    }

    private Map<String, Object> responseBuildNode(RecommendationWorkflowState state) {
        String sessionId = getStateValue(state, "sessionId", "");
        RecommendationCriteria criteria = getStateValue(state, "criteria", new RecommendationCriteria());
        LlmRecommendationContext context = getStateValue(state, "context");
        LlmRecommendationOutput output = getStateValue(state, "output");
        return put("response", buildLlmResponse(sessionId, criteria, context, output));
    }

    private <T> T getStateValue(RecommendationWorkflowState state, String key) {
        return state.<T>value(key).orElse(null);
    }

    private <T> T getStateValue(RecommendationWorkflowState state, String key, T defaultValue) {
        return state.<T>value(key).orElse(defaultValue);
    }

    private Map<String, Object> put(Object... values) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i + 1] != null) {
                map.put(String.valueOf(values[i]), values[i + 1]);
            }
        }
        return map;
    }

    private boolean shouldUseLlm(Long userId) {
        if (!Boolean.TRUE.equals(aiProperties.getEnabled()) || !recommendationLlmClient.isConfigured()) {
            return false;
        }
        if (recommendationLlmCircuitBreaker.isOpen()) {
            return false;
        }
        if (!Boolean.TRUE.equals(aiProperties.getRateLimit().getEnabled())) {
            return true;
        }
        String key = aiProperties.getRateLimit().getKeyPrefix() + ":" + userId;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, aiProperties.getRateLimit().getWindowSeconds(), TimeUnit.SECONDS);
        }
        return count == null || count <= aiProperties.getRateLimit().getMaxRequests();
    }

    private LlmIntentOutput extractIntent(String message, RecommendationCriteria fallbackCriteria) {
        List<RecommendationPromptFactory.ShopTypeView> shopTypes = shopTypeService.lambdaQuery()
                .orderByAsc(ShopType::getSort)
                .list()
                .stream()
                .map(type -> new RecommendationPromptFactory.ShopTypeView(type.getId(), type.getName()))
                .collect(Collectors.toList());
        log.info("Recommendation agent start LLM intent extraction, messageLength={}, shopTypeSize={}",
                StrUtil.length(message), shopTypes.size());
        List<LlmChatMessage> messages = recommendationPromptFactory.buildIntentMessages(message, shopTypes);
        LlmIntentOutput output = callStructured(messages,
                recommendationPromptFactory.intentJsonSchemaResponseFormat(),
                LlmIntentOutput.class);
        if (output.getCriteria() == null) {
            output.setCriteria(fallbackCriteria);
        }
        RecommendationCriteria criteria = output.getCriteria();
        log.info("Recommendation agent LLM intent extracted, safe={}, localLife={}, typeId={}, typeName={}, peopleCount={}, radiusMeters={}, budgetMin={}, budgetMax={}, startTime={}, endTime={}",
                output.getSafe(), output.getLocalLifeRecommendation(),
                criteria == null ? null : criteria.getTypeId(),
                criteria == null ? null : criteria.getTypeName(),
                criteria == null ? null : criteria.getPeopleCount(),
                criteria == null ? null : criteria.getRadiusMeters(),
                criteria == null ? null : criteria.getBudgetMin(),
                criteria == null ? null : criteria.getBudgetMax(),
                criteria == null ? null : criteria.getStartTime(),
                criteria == null ? null : criteria.getEndTime());
        return output;
    }

    private LlmRecommendationOutput recommendByLlm(String message,
                                                   RecommendationCriteria criteria,
                                                   LlmRecommendationContext context) {
        log.info("Recommendation agent start LLM ranking, typeId={}, radiusMeters={}, candidateSize={}, contextChars={}",
                criteria.getTypeId(), criteria.getRadiusMeters(),
                context == null || context.getCandidates() == null ? 0 : context.getCandidates().size(),
                context == null ? 0 : context.getContextChars());
        List<LlmChatMessage> messages = recommendationPromptFactory.buildRecommendationMessages(message, criteria, context);
        LlmRecommendationOutput output = callStructured(messages,
                recommendationPromptFactory.recommendationJsonSchemaResponseFormat(),
                LlmRecommendationOutput.class);
        log.info("Recommendation agent LLM ranking finished, needClarification={}, selectedSize={}, missingFields={}",
                output.getNeedClarification(),
                output.getRecommendations() == null ? 0 : output.getRecommendations().size(),
                output.getMissingFields());
        return output;
    }

    private <T> T callStructured(List<LlmChatMessage> messages,
                                 com.alibaba.fastjson.JSONObject schemaResponseFormat,
                                 Class<T> type) {
        List<com.alibaba.fastjson.JSONObject> responseFormats = new ArrayList<>();
        if (Boolean.TRUE.equals(aiProperties.getJsonSchemaEnabled())) {
            responseFormats.add(schemaResponseFormat);
        }
        responseFormats.add(recommendationPromptFactory.jsonObjectResponseFormat());

        RuntimeException lastError = null;
        for (com.alibaba.fastjson.JSONObject responseFormat : responseFormats) {
            try {
                String content = recommendationLlmClient.chat(messages, defaultChatOptions(responseFormat)).getContent();
                try {
                    return parseJsonContent(content, type);
                } catch (RuntimeException parseError) {
                    lastError = parseError;
                    String repaired = repairJson(content);
                    return parseJsonContent(repaired, type);
                }
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("Structured LLM call failed with responseFormat={}, reason={}",
                        responseFormat.getString("type"), e.getMessage());
            }
        }
        throw lastError == null ? new IllegalStateException("structured llm call failed") : lastError;
    }

    private LlmChatOptions defaultChatOptions(com.alibaba.fastjson.JSONObject responseFormat) {
        return new LlmChatOptions()
                .setResponseFormat(responseFormat);
    }

    private <T> T parseJsonContent(String content, Class<T> type) {
        String json = llmJsonParser.extractJsonObject(content);
        try {
            return new BeanOutputConverter<>(type).convert(json);
        } catch (RuntimeException e) {
            return llmJsonParser.parseObject(json, type);
        }
    }

    private String repairJson(String invalidContent) {
        List<LlmChatMessage> repairMessages = List.of(
                new LlmChatMessage("system", "你是 JSON 修复器。只输出一个合法 JSON 对象，不要输出 Markdown 或解释。"),
                new LlmChatMessage("user", invalidContent)
        );
        return recommendationLlmClient.chat(repairMessages,
                defaultChatOptions(recommendationPromptFactory.jsonObjectResponseFormat())).getContent();
    }

    private RecommendationChatResponse fallbackChat(Long userId,
                                                    String sessionId,
                                                    RecommendationChatRequest request,
                                                    RecommendationCriteria criteria,
                                                    String reason) {
        if (!Boolean.TRUE.equals(aiProperties.getFallbackEnabled()) && !"llm_skipped".equals(reason)) {
            throw new IllegalStateException("LLM unavailable and fallback disabled");
        }
        List<String> missingFields = resolveMissingFields(request, criteria);
        List<RecommendationShopVo> recommendations = missingFields.isEmpty()
                ? recommendByRule(userId, request, criteria, DEFAULT_RECOMMENDATION_SIZE)
                : new ArrayList<>();
        return new RecommendationChatResponse()
                .setSessionId(sessionId)
                .setCriteria(toCriteriaVo(criteria))
                .setRecommendations(recommendations)
                .setNeedClarification(!missingFields.isEmpty())
                .setMissingFields(missingFields)
                .setReply(buildReply(criteria, recommendations, missingFields));
    }

    private List<String> resolveMissingFields(RecommendationChatRequest request, RecommendationCriteria criteria) {
        List<String> missingFields = new ArrayList<>();
        if (criteria.getTypeId() == null) {
            missingFields.add("type");
        }
        if (Boolean.TRUE.equals(criteria.getNeedLocation()) && (request.getX() == null || request.getY() == null)) {
            missingFields.add("location");
        }
        return missingFields;
    }

    private List<LlmRecommendationCandidate> queryCandidates(Long userId,
                                                             RecommendationChatRequest request,
                                                             RecommendationCriteria criteria) {
        List<RecommendationShopVo> shops = recommendByRule(userId, request, criteria,
                Math.max(aiProperties.getMaxCandidates(), aiProperties.getMaxRecommendationSize()));
        if (shops.isEmpty()) {
            return new ArrayList<>();
        }
        return shops.stream()
                .map(shop -> new LlmRecommendationCandidate().setShop(shop))
                .collect(Collectors.toList());
    }

    private GeoRadiusResult geoRadiusShopIds(RecommendationCriteria criteria,
                                             RecommendationChatRequest request,
                                             int limit) {
        if (criteria.getTypeId() == null || request.getX() == null || request.getY() == null) {
            log.info("Recommendation agent skip GEO recall, typeId={}, xPresent={}, yPresent={}",
                    criteria.getTypeId(), request.getX() != null, request.getY() != null);
            return new GeoRadiusResult(new ArrayList<>(), new HashMap<>());
        }
        String key = SHOP_GEO_KEY + criteria.getTypeId();
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(
                        key,
                        new Circle(new Point(request.getX(), request.getY()), new Distance(criteria.getRadiusMeters() / 1000.0, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(Math.min(limit, GEO_CANDIDATE_LIMIT))
                );
        if (results == null || results.getContent().isEmpty()) {
            log.info("Recommendation agent GEO recall empty, key={}, typeId={}, radiusMeters={}, limit={}",
                    key, criteria.getTypeId(), criteria.getRadiusMeters(), Math.min(limit, GEO_CANDIDATE_LIMIT));
            return new GeoRadiusResult(new ArrayList<>(), new HashMap<>());
        }
        List<Long> shopIds = new ArrayList<>();
        Map<Long, Double> distanceMap = new HashMap<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results.getContent()) {
            String shopIdText = result.getContent().getName();
            try {
                Long shopId = Long.valueOf(shopIdText);
                shopIds.add(shopId);
                distanceMap.put(shopId, result.getDistance().getValue() * result.getDistance().getMetric().getMultiplier());
            } catch (NumberFormatException ignored) {
                log.warn("Ignore invalid shop id from redis geo, key={}, shopId={}", key, shopIdText);
            }
        }
        log.info("Recommendation agent GEO recall finished, key={}, typeId={}, radiusMeters={}, resultSize={}",
                key, criteria.getTypeId(), criteria.getRadiusMeters(), shopIds.size());
        return new GeoRadiusResult(shopIds, distanceMap);
    }

    private List<Shop> recallShopDetails(Long typeId, List<Long> shopIds) {
        if (typeId == null || shopIds.isEmpty()) {
            return new ArrayList<>();
        }
        String idStr = shopIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return shopService.lambdaQuery()
                .eq(Shop::getTypeId, typeId)
                .in(Shop::getId, shopIds)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
    }

    private List<RecommendationShopVo> recommendByRule(Long userId,
                                                       RecommendationChatRequest request,
                                                       RecommendationCriteria criteria,
                                                       int limit) {
        GeoRadiusResult geoResult = geoRadiusShopIds(criteria, request, GEO_CANDIDATE_LIMIT);
        if (geoResult.shopIds().isEmpty()) {
            return new ArrayList<>();
        }
        List<Shop> shops = recallShopDetails(criteria.getTypeId(), geoResult.shopIds());
        if (shops.isEmpty()) {
            return new ArrayList<>();
        }
        int maxSold = shops.stream()
                .map(Shop::getSold)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        return shops.stream()
                .map(shop -> toRecommendation(shop, criteria, geoResult.distanceMap().get(shop.getId()), maxSold))
                .filter(Objects::nonNull)
                .sorted(buildComparator(criteria))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private RecommendationShopVo toRecommendation(Shop shop,
                                                  RecommendationCriteria criteria,
                                                  Double distance,
                                                  int maxSold) {
        Long estimatedTotalPrice = estimateTotalPrice(shop.getAvgPrice(), criteria.getPeopleCount());
        if (criteria.getBudgetMin() != null && estimatedTotalPrice != null && estimatedTotalPrice < criteria.getBudgetMin()) {
            return null;
        }
        if (criteria.getBudgetMax() != null && estimatedTotalPrice != null && estimatedTotalPrice > criteria.getBudgetMax()) {
            return null;
        }
        if (!matchOpenHours(shop, criteria)) {
            return null;
        }
        double compositeScore = calculateCompositeScore(shop, maxSold);
        double rankScore = calculateRankScore(compositeScore, distance, criteria, estimatedTotalPrice);

        return new RecommendationShopVo()
                .setShopId(shop.getId())
                .setName(shop.getName())
                .setTypeName(criteria.getTypeName())
                .setArea(shop.getArea())
                .setAddress(shop.getAddress())
                .setImage(firstImage(shop.getImages()))
                .setScore(shop.getScore() == null ? null : BigDecimal.valueOf(shop.getScore())
                        .divide(BigDecimal.TEN, 1, RoundingMode.HALF_UP)
                        .doubleValue())
                .setComments(shop.getComments())
                .setAvgPrice(shop.getAvgPrice())
                .setDistance(distance)
                .setEstimatedTotalPrice(estimatedTotalPrice)
                .setCompositeScore(round4(compositeScore))
                .setRankScore(round4(rankScore))
                .setHasHistoryOrder(false)
                .setReason(buildReason(shop, criteria, distance, estimatedTotalPrice, false));
    }

    private RecommendationChatResponse buildLlmResponse(String sessionId,
                                                        RecommendationCriteria criteria,
                                                        LlmRecommendationContext context,
                                                        LlmRecommendationOutput output) {
        Map<Long, RecommendationShopVo> candidateMap = context.getCandidates()
                .stream()
                .map(LlmRecommendationCandidate::getShop)
                .collect(Collectors.toMap(RecommendationShopVo::getShopId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<RecommendationShopVo> selected = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        if (output.getRecommendations() != null) {
            for (LlmRecommendationOutput.SelectedShop item : output.getRecommendations()) {
                if (item == null || item.getShopId() == null || seen.contains(item.getShopId())) {
                    continue;
                }
                RecommendationShopVo shop = candidateMap.get(item.getShopId());
                if (shop == null) {
                    continue;
                }
                selected.add(copyShopWithReason(shop, StrUtil.blankToDefault(item.getReason(), shop.getReason())));
                seen.add(item.getShopId());
                if (selected.size() >= aiProperties.getMaxRecommendationSize()) {
                    break;
                }
            }
        }
        if (selected.isEmpty() && !Boolean.TRUE.equals(output.getNeedClarification())) {
            throw new IllegalStateException("LLM selected no valid candidate shops");
        }
        log.info("Recommendation agent response built, sessionId={}, selectedSize={}, needClarification={}",
                sessionId, selected.size(), output.getNeedClarification());
        return new RecommendationChatResponse()
                .setSessionId(sessionId)
                .setCriteria(toCriteriaVo(criteria))
                .setRecommendations(selected)
                .setNeedClarification(Boolean.TRUE.equals(output.getNeedClarification()))
                .setMissingFields(output.getMissingFields() == null ? new ArrayList<>() : output.getMissingFields())
                .setReply(StrUtil.blankToDefault(output.getReply(), buildReply(criteria, selected, new ArrayList<>())));
    }

    private RecommendationShopVo copyShopWithReason(RecommendationShopVo source, String reason) {
        return new RecommendationShopVo()
                .setShopId(source.getShopId())
                .setName(source.getName())
                .setTypeName(source.getTypeName())
                .setArea(source.getArea())
                .setAddress(source.getAddress())
                .setImage(source.getImage())
                .setScore(source.getScore())
                .setComments(source.getComments())
                .setAvgPrice(source.getAvgPrice())
                .setDistance(source.getDistance())
                .setEstimatedTotalPrice(source.getEstimatedTotalPrice())
                .setCompositeScore(source.getCompositeScore())
                .setRankScore(source.getRankScore())
                .setHasHistoryOrder(source.getHasHistoryOrder())
                .setReason(reason);
    }

    private Comparator<RecommendationShopVo> buildComparator(RecommendationCriteria criteria) {
        Comparator<RecommendationShopVo> comparator;
        if ("distance".equals(criteria.getSortBy())) {
            comparator = Comparator.comparing(shop -> shop.getDistance() == null ? Double.MAX_VALUE : shop.getDistance());
        } else if ("price".equals(criteria.getSortBy())) {
            comparator = Comparator.comparing(shop -> shop.getAvgPrice() == null ? Long.MAX_VALUE : shop.getAvgPrice());
        } else {
            comparator = Comparator.comparing(
                    RecommendationShopVo::getRankScore,
                    Comparator.nullsLast(Comparator.reverseOrder())
            );
        }
        return comparator
                .thenComparing(shop -> shop.getDistance() == null ? Double.MAX_VALUE : shop.getDistance())
                .thenComparing(shop -> shop.getAvgPrice() == null ? Long.MAX_VALUE : shop.getAvgPrice());
    }

    private RecommendationCriteria normalizeCriteria(RecommendationCriteria criteria, RecommendationCriteria fallback) {
        RecommendationCriteria source = criteria == null ? new RecommendationCriteria() : criteria;
        RecommendationCriteria base = fallback == null ? new RecommendationCriteria() : fallback;
        RecommendationCriteria normalized = new RecommendationCriteria()
                .setTypeId(firstNonNull(source.getTypeId(), base.getTypeId(), null))
                .setTypeName(StrUtil.blankToDefault(source.getTypeName(), base.getTypeName()))
                .setPeopleCount(firstNonNull(source.getPeopleCount(), base.getPeopleCount(), 1))
                .setBudgetMin(firstNonNull(source.getBudgetMin(), base.getBudgetMin(), null))
                .setBudgetMax(firstNonNull(source.getBudgetMax(), base.getBudgetMax(), null))
                .setBudgetPreference(StrUtil.blankToDefault(source.getBudgetPreference(), base.getBudgetPreference()))
                .setPerCapitaBudgetMin(firstNonNull(source.getPerCapitaBudgetMin(), base.getPerCapitaBudgetMin(), null))
                .setPerCapitaBudgetMax(firstNonNull(source.getPerCapitaBudgetMax(), base.getPerCapitaBudgetMax(), null))
                .setDistanceLevel(StrUtil.blankToDefault(source.getDistanceLevel(), base.getDistanceLevel()))
                .setRadiusMeters(firstNonNull(source.getRadiusMeters(), base.getRadiusMeters(), DEFAULT_RADIUS_METERS))
                .setStartTime(StrUtil.blankToDefault(source.getStartTime(), base.getStartTime()))
                .setEndTime(StrUtil.blankToDefault(source.getEndTime(), base.getEndTime()))
                .setSortBy(StrUtil.blankToDefault(source.getSortBy(), StrUtil.blankToDefault(base.getSortBy(), "compositeScore")))
                .setNeedLocation(firstNonNull(source.getNeedLocation(), base.getNeedLocation(), true));

        if (normalized.getPeopleCount() == null || normalized.getPeopleCount() <= 0) {
            normalized.setPeopleCount(1);
        }
        normalizeShopType(normalized);
        normalized.setRadiusMeters(normalizeRadiusLevel(normalized.getRadiusMeters()));
        normalized.setDistanceLevel(normalized.getRadiusMeters() / 1000 + "km");
        if (!ALLOWED_SORTS.contains(normalized.getSortBy())) {
            normalized.setSortBy("compositeScore");
        }
        if (normalized.getBudgetMin() != null && normalized.getBudgetMax() != null
                && normalized.getBudgetMin() > normalized.getBudgetMax()) {
            Long temp = normalized.getBudgetMin();
            normalized.setBudgetMin(normalized.getBudgetMax());
            normalized.setBudgetMax(temp);
        }
        resolvePerCapitaBudget(normalized);
        return normalized;
    }

    private void normalizeShopType(RecommendationCriteria criteria) {
        if (criteria.getTypeId() != null) {
            ShopType type = shopTypeService.getById(criteria.getTypeId());
            if (type != null) {
                if (StrUtil.isBlank(criteria.getTypeName())) {
                    criteria.setTypeName(type.getName());
                }
                return;
            }
        }
        if (StrUtil.isBlank(criteria.getTypeName())) {
            criteria.setTypeId(null);
            return;
        }
        for (ShopType type : shopTypeService.list()) {
            if (type.getName() != null && (type.getName().contains(criteria.getTypeName())
                    || criteria.getTypeName().contains(type.getName()))) {
                criteria.setTypeId(type.getId());
                criteria.setTypeName(type.getName());
                return;
            }
        }
        criteria.setTypeId(null);
    }

    private int normalizeRadiusLevel(Integer radiusMeters) {
        if (radiusMeters == null || radiusMeters <= 0) {
            return DEFAULT_RADIUS_METERS;
        }
        if (ALLOWED_RADIUS_METERS.contains(radiusMeters)) {
            return radiusMeters;
        }
        if (radiusMeters <= 3000) {
            return 3000;
        }
        if (radiusMeters <= 5000) {
            return 5000;
        }
        return 10000;
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private List<String> mergeMissingFields(List<String> left, List<String> right) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (left != null) {
            fields.addAll(left);
        }
        if (right != null) {
            fields.addAll(right);
        }
        fields.removeIf(StrUtil::isBlank);
        return new ArrayList<>(fields);
    }

    private RecommendationChatResponse clarificationResponse(String sessionId,
                                                             RecommendationCriteria criteria,
                                                             List<String> missingFields,
                                                             String reply) {
        return new RecommendationChatResponse()
                .setSessionId(sessionId)
                .setCriteria(toCriteriaVo(criteria))
                .setRecommendations(new ArrayList<>())
                .setNeedClarification(true)
                .setMissingFields(missingFields)
                .setReply(reply);
    }

    private RecommendationChatResponse emptyRecommendationResponse(String sessionId, RecommendationCriteria criteria) {
        return new RecommendationChatResponse()
                .setSessionId(sessionId)
                .setCriteria(toCriteriaVo(criteria))
                .setRecommendations(new ArrayList<>())
                .setNeedClarification(false)
                .setMissingFields(new ArrayList<>())
                .setReply(buildReply(criteria, new ArrayList<>(), new ArrayList<>()));
    }

    private RecommendationChatResponse safetyResponse(String sessionId, RecommendationCriteria criteria) {
        return safetyResponse(sessionId, criteria,
                "我只能帮你做本地生活门店、优惠和消费推荐。可以告诉我想去的类型、预算、人数和距离。");
    }

    private RecommendationChatResponse safetyResponse(String sessionId, RecommendationCriteria criteria, String reply) {
        return new RecommendationChatResponse()
                .setSessionId(sessionId)
                .setCriteria(toCriteriaVo(criteria))
                .setRecommendations(new ArrayList<>())
                .setNeedClarification(true)
                .setMissingFields(List.of("recommendation_request"))
                .setReply(reply);
    }

    private boolean isUnsafeInput(String message) {
        if (StrUtil.isBlank(message)) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("api key")
                || lower.contains("apikey")
                || lower.contains("密码")
                || lower.contains("盗号")
                || lower.contains("木马")
                || lower.contains("破解")
                || lower.contains("绕过")
                || lower.contains("身份证")
                || lower.contains("银行卡")
                || lower.contains("毒品");
    }

    private String normalizeSessionId(String rawSessionId) {
        String sessionId = StrUtil.blankToDefault(rawSessionId, UUID.randomUUID().toString());
        if (sessionId.length() > aiProperties.getMaxSessionIdChars()) {
            return UUID.randomUUID().toString();
        }
        return sessionId;
    }

    private String buildReason(Shop shop,
                               RecommendationCriteria criteria,
                               Double distance,
                               Long estimatedTotalPrice,
                               boolean hasHistoryOrder) {
        List<String> reasons = new ArrayList<>();
        if (hasHistoryOrder) {
            reasons.add("你之前购买过这家店的优惠券");
        }
        if (shop.getScore() != null) {
            reasons.add("评分约" + BigDecimal.valueOf(shop.getScore()).divide(BigDecimal.TEN, 1, RoundingMode.HALF_UP) + "分");
        }
        if (distance != null) {
            reasons.add("距离约" + formatDistance(distance));
        }
        if (estimatedTotalPrice != null) {
            reasons.add(criteria.getPeopleCount() + "人预估总价约" + estimatedTotalPrice + "元");
        }
        if (criteria.getStartTime() != null && criteria.getEndTime() != null) {
            reasons.add("营业时间可参考：" + shop.getOpenHours());
        }
        return String.join("，", reasons);
    }

    private String buildClarificationReply(RecommendationCriteria criteria, List<String> missingFields) {
        if (missingFields.contains("location")) {
            return "我已经理解你的需求了，还需要获取当前位置后才能筛选"
                    + criteria.getRadiusMeters() / 1000 + "公里内的店铺。请允许定位，或补充你所在的商圈。";
        }
        return "我还需要你补充" + String.join("、", missingFields) + "，这样才能继续筛选合适的店。";
    }

    private String buildReply(RecommendationCriteria criteria,
                              List<RecommendationShopVo> recommendations,
                              List<String> missingFields) {
        if (!missingFields.isEmpty()) {
            return buildClarificationReply(criteria, missingFields);
        }
        if (recommendations.isEmpty()) {
            return "我按" + criteria.getTypeName() + "、" + criteria.getPeopleCount() + "人、"
                    + criteria.getRadiusMeters() / 1000 + "公里内"
                    + buildBudgetText(criteria) + "筛了一遍，暂时没有完全符合的店铺。可以放宽预算或距离再试一次。";
        }
        String historyText = recommendations.stream().anyMatch(item -> Boolean.TRUE.equals(item.getHasHistoryOrder()))
                ? "其中有你之前购买过券的店，我也帮你标出来了。"
                : "";
        return "我按" + criteria.getTypeName() + "、" + criteria.getPeopleCount() + "人、"
                + criteria.getRadiusMeters() / 1000 + "公里内"
                + buildBudgetText(criteria) + "优先推荐了这些店。" + historyText;
    }

    private String buildBudgetText(RecommendationCriteria criteria) {
        if (criteria.getBudgetMin() != null && criteria.getBudgetMax() != null) {
            return "、总价" + criteria.getBudgetMin() + "到" + criteria.getBudgetMax() + "元、";
        }
        if (criteria.getBudgetMax() != null) {
            return "、总价" + criteria.getBudgetMax() + "元以内、";
        }
        return "、";
    }

    private RecommendationCriteriaVo toCriteriaVo(RecommendationCriteria criteria) {
        return new RecommendationCriteriaVo()
                .setTypeId(criteria.getTypeId())
                .setTypeName(criteria.getTypeName())
                .setPeopleCount(criteria.getPeopleCount())
                .setBudgetMin(criteria.getBudgetMin())
                .setBudgetMax(criteria.getBudgetMax())
                .setBudgetPreference(criteria.getBudgetPreference())
                .setPerCapitaBudgetMin(criteria.getPerCapitaBudgetMin())
                .setPerCapitaBudgetMax(criteria.getPerCapitaBudgetMax())
                .setDistanceLevel(criteria.getDistanceLevel())
                .setRadiusMeters(criteria.getRadiusMeters())
                .setStartTime(criteria.getStartTime())
                .setEndTime(criteria.getEndTime())
                .setSortBy(criteria.getSortBy());
    }

    private Long estimateTotalPrice(Long avgPrice, Integer peopleCount) {
        if (avgPrice == null) {
            return null;
        }
        int count = peopleCount == null || peopleCount <= 0 ? 1 : peopleCount;
        return avgPrice * count;
    }

    private String firstImage(String images) {
        if (StrUtil.isBlank(images)) {
            return "";
        }
        return images.split(",")[0];
    }

    private String formatDistance(Double distance) {
        if (distance < 1000) {
            return BigDecimal.valueOf(distance).setScale(0, RoundingMode.HALF_UP) + "米";
        }
        return BigDecimal.valueOf(distance / 1000).setScale(1, RoundingMode.HALF_UP) + "公里";
    }

    private Double calculateDistanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6371000;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lngDistance = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }

    private boolean matchOpenHours(Shop shop, RecommendationCriteria criteria) {
        if (StrUtil.isBlank(criteria.getStartTime()) && StrUtil.isBlank(criteria.getEndTime())) {
            return true;
        }
        if (StrUtil.isBlank(shop.getOpenHours()) || !shop.getOpenHours().contains("-")) {
            return false;
        }
        try {
            String[] parts = shop.getOpenHours().trim().split("-");
            if (parts.length < 2) {
                return false;
            }
            LocalTime openStart = LocalTime.parse(parts[0].trim());
            LocalTime openEnd = LocalTime.parse(parts[1].trim());
            LocalTime userStart = StrUtil.isBlank(criteria.getStartTime()) ? openStart : LocalTime.parse(criteria.getStartTime());
            LocalTime userEnd = StrUtil.isBlank(criteria.getEndTime()) ? userStart : LocalTime.parse(criteria.getEndTime());
            if (openEnd.isBefore(openStart)) {
                return !userStart.isBefore(openStart) || !userEnd.isAfter(openEnd);
            }
            return !openStart.isAfter(userStart) && !openEnd.isBefore(userEnd);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private double calculateCompositeScore(Shop shop, int maxSold) {
        double scoreNorm = shop.getScore() == null ? 0 : Math.min(shop.getScore() / 50.0, 1.0);
        double soldNorm = shop.getSold() == null || maxSold <= 0
                ? 0
                : Math.log1p(shop.getSold()) / Math.log1p(maxSold);
        return 0.45 * scoreNorm + 0.55 * soldNorm + 0.20 * scoreNorm * soldNorm;
    }

    private double calculateRankScore(double compositeScore,
                                      Double distance,
                                      RecommendationCriteria criteria,
                                      Long estimatedTotalPrice) {
        double distanceScore = distance == null || criteria.getRadiusMeters() == null
                ? 0
                : 1 - Math.min(distance / criteria.getRadiusMeters(), 1);
        double budgetScore = 1;
        if ((criteria.getBudgetMin() != null || criteria.getBudgetMax() != null) && estimatedTotalPrice == null) {
            budgetScore = 0;
        }
        return compositeScore * 0.70 + distanceScore * 0.20 + budgetScore * 0.10;
    }

    private double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private void resolvePerCapitaBudget(RecommendationCriteria criteria) {
        int peopleCount = criteria.getPeopleCount() == null || criteria.getPeopleCount() <= 0
                ? 1
                : criteria.getPeopleCount();
        if (criteria.getBudgetMin() != null) {
            criteria.setPerCapitaBudgetMin((long) Math.ceil(criteria.getBudgetMin() * 1.0 / peopleCount));
        }
        if (criteria.getBudgetMax() != null) {
            criteria.setPerCapitaBudgetMax(criteria.getBudgetMax() / peopleCount);
        }
    }

    private void saveConversation(Long userId,
                                  String sessionId,
                                  RecommendationChatRequest request,
                                  RecommendationChatResponse response) {
        String key = CHAT_KEY_PREFIX + userId + ":" + sessionId;
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(new ChatSnapshot(request, response)),
                CHAT_TTL_HOURS, TimeUnit.HOURS);
        if (response != null && response.getCriteria() != null) {
            stringRedisTemplate.opsForValue().set(sessionCriteriaKey(sessionId), JSON.toJSONString(response.getCriteria()),
                    CHAT_TTL_HOURS, TimeUnit.HOURS);
        }
    }

    private RecommendationCriteria loadSessionCriteria(String sessionId) {
        String json = stringRedisTemplate.opsForValue().get(sessionCriteriaKey(sessionId));
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            RecommendationCriteriaVo vo = JSON.parseObject(json, RecommendationCriteriaVo.class);
            return new RecommendationCriteria()
                    .setTypeId(vo.getTypeId())
                    .setTypeName(vo.getTypeName())
                    .setPeopleCount(vo.getPeopleCount())
                    .setBudgetMin(vo.getBudgetMin())
                    .setBudgetMax(vo.getBudgetMax())
                    .setBudgetPreference(vo.getBudgetPreference())
                    .setPerCapitaBudgetMin(vo.getPerCapitaBudgetMin())
                    .setPerCapitaBudgetMax(vo.getPerCapitaBudgetMax())
                    .setDistanceLevel(vo.getDistanceLevel())
                    .setRadiusMeters(vo.getRadiusMeters())
                    .setStartTime(vo.getStartTime())
                    .setEndTime(vo.getEndTime())
                    .setSortBy(vo.getSortBy())
                    .setNeedLocation(true);
        } catch (RuntimeException e) {
            log.warn("Load recommendation session criteria failed, sessionId={}", sessionId, e);
            return null;
        }
    }

    private String sessionCriteriaKey(String sessionId) {
        return SESSION_CRITERIA_KEY_PREFIX + sessionId;
    }

    private record ChatSnapshot(RecommendationChatRequest request, RecommendationChatResponse response) {
    }

    private record GeoRadiusResult(List<Long> shopIds, Map<Long, Double> distanceMap) {
    }

    public static class RecommendationWorkflowState extends AgentState {

        public RecommendationWorkflowState(Map<String, Object> initData) {
            super(initData);
        }
    }
}

