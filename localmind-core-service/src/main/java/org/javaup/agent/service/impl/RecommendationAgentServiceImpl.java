package org.javaup.agent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.agent.config.LocalMindAiProperties;
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
import org.javaup.agent.parser.RecommendationIntentParser;
import org.javaup.agent.service.IRecommendationAgentService;
import org.javaup.agent.service.RecommendationContextBuilder;
import org.javaup.agent.vo.RecommendationChatResponse;
import org.javaup.agent.vo.RecommendationCriteriaVo;
import org.javaup.agent.vo.RecommendationShopVo;
import org.javaup.entity.Shop;
import org.javaup.entity.ShopType;
import org.javaup.entity.Voucher;
import org.javaup.entity.VoucherOrder;
import org.javaup.enums.OrderStatus;
import org.javaup.enums.VoucherStatus;
import org.javaup.service.IShopService;
import org.javaup.service.IShopTypeService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherService;
import org.javaup.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

@Slf4j
@Service
public class RecommendationAgentServiceImpl implements IRecommendationAgentService {

    private static final String CHAT_KEY_PREFIX = "agent:recommendation:chat:";
    private static final long CHAT_TTL_HOURS = 1L;
    private static final int DEFAULT_RECOMMENDATION_SIZE = 5;
    private static final int DEFAULT_RADIUS_METERS = 5000;
    private static final int MAX_RADIUS_METERS = 20000;
    private static final Set<String> ALLOWED_SORTS = Set.of("score", "distance", "price");

    @Resource
    private RecommendationIntentParser recommendationIntentParser;

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
    private LocalMindAiProperties aiProperties;

    @Resource
    private IShopService shopService;

    @Resource
    private IShopTypeService shopTypeService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public RecommendationChatResponse chat(RecommendationChatRequest request) {
        Long userId = UserHolder.getUser().getId();
        String sessionId = normalizeSessionId(request.getSessionId());
        RecommendationCriteria ruleCriteria = normalizeCriteria(recommendationIntentParser.parse(request.getMessage()), null);

        RecommendationChatResponse response;
        if (StrUtil.length(request.getMessage()) > aiProperties.getMaxMessageChars()) {
            response = clarificationResponse(sessionId, ruleCriteria, List.of("message"),
                    "这段需求有点长，请把人数、预算、距离和想去的类型压缩成一句话再发我。");
            saveConversation(userId, sessionId, request, response);
            return response;
        }
        if (isUnsafeInput(request.getMessage())) {
            response = safetyResponse(sessionId, ruleCriteria);
            saveConversation(userId, sessionId, request, response);
            return response;
        }

        if (!shouldUseLlm(userId)) {
            response = fallbackChat(userId, sessionId, request, ruleCriteria, "llm_skipped");
            saveConversation(userId, sessionId, request, response);
            return response;
        }

        try {
            LlmIntentOutput intent = extractIntent(request.getMessage(), ruleCriteria);
            if (!Boolean.TRUE.equals(intent.getSafe()) || !Boolean.TRUE.equals(intent.getLocalLifeRecommendation())) {
                response = safetyResponse(sessionId, ruleCriteria, StrUtil.blankToDefault(intent.getSafetyReply(),
                        "我只能帮你做本地生活门店、优惠和消费推荐。可以告诉我想去的类型、预算、人数和距离。"));
                saveConversation(userId, sessionId, request, response);
                recommendationLlmCircuitBreaker.recordSuccess();
                return response;
            }

            RecommendationCriteria criteria = normalizeCriteria(intent.getCriteria(), ruleCriteria);
            List<String> missingFields = mergeMissingFields(intent.getMissingFields(), resolveMissingFields(request, criteria));
            if (!missingFields.isEmpty()) {
                response = clarificationResponse(sessionId, criteria, missingFields, buildClarificationReply(criteria, missingFields));
                saveConversation(userId, sessionId, request, response);
                recommendationLlmCircuitBreaker.recordSuccess();
                return response;
            }

            List<LlmRecommendationCandidate> candidates = queryCandidates(userId, request, criteria);
            if (candidates.isEmpty()) {
                response = emptyRecommendationResponse(sessionId, criteria);
                saveConversation(userId, sessionId, request, response);
                recommendationLlmCircuitBreaker.recordSuccess();
                return response;
            }

            LlmRecommendationContext context = recommendationContextBuilder.build(candidates, criteria);
            if (context.getCandidates().isEmpty()) {
                throw new IllegalStateException("candidate context exceeds max size");
            }
            LlmRecommendationOutput output = recommendByLlm(request.getMessage(), criteria, context);
            response = buildLlmResponse(sessionId, criteria, context, output);
            saveConversation(userId, sessionId, request, response);
            recommendationLlmCircuitBreaker.recordSuccess();
            return response;
        } catch (Exception e) {
            log.warn("Recommendation LLM flow failed, fallback to rule flow, userId={}, sessionId={}, reason={}",
                    userId, sessionId, e.getMessage(), e);
            recommendationLlmCircuitBreaker.recordFailure(e.getClass().getSimpleName());
            response = fallbackChat(userId, sessionId, request, ruleCriteria, "llm_failed");
            saveConversation(userId, sessionId, request, response);
            return response;
        }
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

    private LlmIntentOutput extractIntent(String message, RecommendationCriteria ruleCriteria) {
        List<RecommendationPromptFactory.ShopTypeView> shopTypes = shopTypeService.lambdaQuery()
                .orderByAsc(ShopType::getSort)
                .list()
                .stream()
                .map(type -> new RecommendationPromptFactory.ShopTypeView(type.getId(), type.getName()))
                .collect(Collectors.toList());
        List<LlmChatMessage> messages = recommendationPromptFactory.buildIntentMessages(message, shopTypes);
        LlmIntentOutput output = callStructured(messages,
                recommendationPromptFactory.intentJsonSchemaResponseFormat(),
                LlmIntentOutput.class);
        if (output.getCriteria() == null) {
            output.setCriteria(ruleCriteria);
        }
        return output;
    }

    private LlmRecommendationOutput recommendByLlm(String message,
                                                   RecommendationCriteria criteria,
                                                   LlmRecommendationContext context) {
        List<LlmChatMessage> messages = recommendationPromptFactory.buildRecommendationMessages(message, criteria, context);
        return callStructured(messages,
                recommendationPromptFactory.recommendationJsonSchemaResponseFormat(),
                LlmRecommendationOutput.class);
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
                .setModel(aiProperties.getChat().getModel())
                .setTemperature(aiProperties.getChat().getTemperature())
                .setMaxTokens(aiProperties.getChat().getMaxTokens())
                .setResponseFormat(responseFormat);
    }

    private <T> T parseJsonContent(String content, Class<T> type) {
        return llmJsonParser.parseObject(content, type);
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
        if (Boolean.TRUE.equals(criteria.getNeedLocation()) && (request.getX() == null || request.getY() == null)) {
            missingFields.add("location");
        }
        return missingFields;
    }

    private List<LlmRecommendationCandidate> queryCandidates(Long userId,
                                                             RecommendationChatRequest request,
                                                             RecommendationCriteria criteria) {
        List<RecommendationShopVo> shops = recommendByRule(userId, request, criteria,
                Math.max(aiProperties.getMaxCandidates() * 2, aiProperties.getMaxRecommendationSize()));
        if (shops.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> shopIds = shops.stream()
                .map(RecommendationShopVo::getShopId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<Long, Shop> shopMap = shopService.lambdaQuery()
                .in(Shop::getId, shopIds)
                .list()
                .stream()
                .collect(Collectors.toMap(Shop::getId, Function.identity(), (left, right) -> left));
        Map<Long, List<Voucher>> voucherMap = queryVoucherMap(shopIds);
        return shops.stream()
                .map(shop -> {
                    Shop raw = shopMap.get(shop.getShopId());
                    List<LlmRecommendationCandidate.VoucherDigest> vouchers = voucherMap
                            .getOrDefault(shop.getShopId(), List.of())
                            .stream()
                            .limit(aiProperties.getMaxVouchersPerShop())
                            .map(LlmRecommendationCandidate.VoucherDigest::from)
                            .collect(Collectors.toList());
                    return new LlmRecommendationCandidate()
                            .setShop(shop)
                            .setOpenHours(raw == null ? null : raw.getOpenHours())
                            .setVouchers(vouchers);
                })
                .collect(Collectors.toList());
    }

    private Map<Long, List<Voucher>> queryVoucherMap(List<Long> shopIds) {
        if (shopIds.isEmpty()) {
            return new HashMap<>();
        }
        List<Voucher> vouchers = voucherService.lambdaQuery()
                .in(Voucher::getShopId, shopIds)
                .eq(Voucher::getStatus, VoucherStatus.AVAILABLE.getCode())
                .list();
        return vouchers.stream().collect(Collectors.groupingBy(Voucher::getShopId));
    }

    private List<RecommendationShopVo> recommendByRule(Long userId,
                                                       RecommendationChatRequest request,
                                                       RecommendationCriteria criteria,
                                                       int limit) {
        List<Shop> shops = shopService.lambdaQuery()
                .eq(Shop::getTypeId, criteria.getTypeId())
                .list();
        Set<Long> historyShopIds = queryHistoryShopIds(userId);
        return shops.stream()
                .map(shop -> toRecommendation(shop, criteria, request, historyShopIds.contains(shop.getId())))
                .filter(Objects::nonNull)
                .sorted(buildComparator(criteria))
                .limit(limit)
                .collect(Collectors.toList());
    }

    private RecommendationShopVo toRecommendation(Shop shop,
                                                  RecommendationCriteria criteria,
                                                  RecommendationChatRequest request,
                                                  boolean hasHistoryOrder) {
        Double distance = null;
        if (request.getX() != null && request.getY() != null && shop.getX() != null && shop.getY() != null) {
            distance = calculateDistanceMeters(request.getY(), request.getX(), shop.getY(), shop.getX());
            if (criteria.getRadiusMeters() != null && distance > criteria.getRadiusMeters()) {
                return null;
            }
        }

        Long estimatedTotalPrice = estimateTotalPrice(shop.getAvgPrice(), criteria.getPeopleCount());
        if (criteria.getBudgetMin() != null && estimatedTotalPrice != null && estimatedTotalPrice < criteria.getBudgetMin()) {
            return null;
        }
        if (criteria.getBudgetMax() != null && estimatedTotalPrice != null && estimatedTotalPrice > criteria.getBudgetMax()) {
            return null;
        }

        return new RecommendationShopVo()
                .setShopId(shop.getId())
                .setName(shop.getName())
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
                .setHasHistoryOrder(hasHistoryOrder)
                .setReason(buildReason(shop, criteria, distance, estimatedTotalPrice, hasHistoryOrder));
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
                .setArea(source.getArea())
                .setAddress(source.getAddress())
                .setImage(source.getImage())
                .setScore(source.getScore())
                .setComments(source.getComments())
                .setAvgPrice(source.getAvgPrice())
                .setDistance(source.getDistance())
                .setEstimatedTotalPrice(source.getEstimatedTotalPrice())
                .setHasHistoryOrder(source.getHasHistoryOrder())
                .setReason(reason);
    }

    private Set<Long> queryHistoryShopIds(Long userId) {
        List<VoucherOrder> orders = voucherOrderService.lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getStatus, OrderStatus.NORMAL.getCode())
                .list();
        if (orders.isEmpty()) {
            return new HashSet<>();
        }
        List<Long> voucherIds = orders.stream()
                .map(VoucherOrder::getVoucherId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (voucherIds.isEmpty()) {
            return new HashSet<>();
        }
        return voucherService.lambdaQuery()
                .in(Voucher::getId, voucherIds)
                .list()
                .stream()
                .map(Voucher::getShopId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private Comparator<RecommendationShopVo> buildComparator(RecommendationCriteria criteria) {
        Comparator<RecommendationShopVo> comparator = Comparator
                .comparing((RecommendationShopVo shop) -> Boolean.TRUE.equals(shop.getHasHistoryOrder())).reversed();
        if ("distance".equals(criteria.getSortBy())) {
            comparator = comparator.thenComparing(shop -> shop.getDistance() == null ? Double.MAX_VALUE : shop.getDistance());
        } else if ("price".equals(criteria.getSortBy())) {
            comparator = comparator.thenComparing(shop -> shop.getAvgPrice() == null ? Long.MAX_VALUE : shop.getAvgPrice());
        } else {
            comparator = comparator.thenComparing(
                    RecommendationShopVo::getScore,
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
                .setTypeId(firstNonNull(source.getTypeId(), base.getTypeId(), 2L))
                .setTypeName(StrUtil.blankToDefault(source.getTypeName(), StrUtil.blankToDefault(base.getTypeName(), "KTV")))
                .setPeopleCount(firstNonNull(source.getPeopleCount(), base.getPeopleCount(), 1))
                .setBudgetMin(firstNonNull(source.getBudgetMin(), base.getBudgetMin(), null))
                .setBudgetMax(firstNonNull(source.getBudgetMax(), base.getBudgetMax(), null))
                .setPerCapitaBudgetMin(firstNonNull(source.getPerCapitaBudgetMin(), base.getPerCapitaBudgetMin(), null))
                .setPerCapitaBudgetMax(firstNonNull(source.getPerCapitaBudgetMax(), base.getPerCapitaBudgetMax(), null))
                .setRadiusMeters(firstNonNull(source.getRadiusMeters(), base.getRadiusMeters(), DEFAULT_RADIUS_METERS))
                .setStartTime(StrUtil.blankToDefault(source.getStartTime(), base.getStartTime()))
                .setEndTime(StrUtil.blankToDefault(source.getEndTime(), base.getEndTime()))
                .setSortBy(StrUtil.blankToDefault(source.getSortBy(), StrUtil.blankToDefault(base.getSortBy(), "score")))
                .setNeedLocation(firstNonNull(source.getNeedLocation(), base.getNeedLocation(), true));

        if (normalized.getPeopleCount() == null || normalized.getPeopleCount() <= 0) {
            normalized.setPeopleCount(1);
        }
        if (normalized.getRadiusMeters() == null || normalized.getRadiusMeters() <= 0) {
            normalized.setRadiusMeters(DEFAULT_RADIUS_METERS);
        }
        normalized.setRadiusMeters(Math.min(normalized.getRadiusMeters(), MAX_RADIUS_METERS));
        if (!ALLOWED_SORTS.contains(normalized.getSortBy())) {
            normalized.setSortBy("score");
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
                .setPerCapitaBudgetMin(criteria.getPerCapitaBudgetMin())
                .setPerCapitaBudgetMax(criteria.getPerCapitaBudgetMax())
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
    }

    private record ChatSnapshot(RecommendationChatRequest request, RecommendationChatResponse response) {
    }
}
