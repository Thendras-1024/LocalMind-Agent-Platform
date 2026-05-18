package org.javaup.agent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import org.javaup.agent.dto.RecommendationChatRequest;
import org.javaup.agent.model.RecommendationCriteria;
import org.javaup.agent.parser.RecommendationIntentParser;
import org.javaup.agent.service.IRecommendationAgentService;
import org.javaup.agent.vo.RecommendationChatResponse;
import org.javaup.agent.vo.RecommendationCriteriaVo;
import org.javaup.agent.vo.RecommendationShopVo;
import org.javaup.entity.Shop;
import org.javaup.entity.Voucher;
import org.javaup.entity.VoucherOrder;
import org.javaup.enums.OrderStatus;
import org.javaup.service.IShopService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherService;
import org.javaup.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class RecommendationAgentServiceImpl implements IRecommendationAgentService {

    private static final String CHAT_KEY_PREFIX = "agent:recommendation:chat:";
    private static final long CHAT_TTL_HOURS = 1L;
    private static final int MAX_RECOMMENDATION_SIZE = 5;

    @Resource
    private RecommendationIntentParser recommendationIntentParser;

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public RecommendationChatResponse chat(RecommendationChatRequest request) {
        Long userId = UserHolder.getUser().getId();
        String sessionId = StrUtil.blankToDefault(request.getSessionId(), UUID.randomUUID().toString());
        RecommendationCriteria criteria = recommendationIntentParser.parse(request.getMessage());
        List<String> missingFields = resolveMissingFields(request, criteria);

        List<RecommendationShopVo> recommendations = missingFields.isEmpty()
                ? recommend(userId, request, criteria)
                : new ArrayList<>();

        RecommendationChatResponse response = new RecommendationChatResponse()
                .setSessionId(sessionId)
                .setCriteria(toCriteriaVo(criteria))
                .setRecommendations(recommendations)
                .setNeedClarification(!missingFields.isEmpty())
                .setMissingFields(missingFields)
                .setReply(buildReply(criteria, recommendations, missingFields));

        saveConversation(userId, sessionId, request, response);
        return response;
    }

    private List<String> resolveMissingFields(RecommendationChatRequest request, RecommendationCriteria criteria) {
        List<String> missingFields = new ArrayList<>();
        if (criteria.getNeedLocation() && (request.getX() == null || request.getY() == null)) {
            missingFields.add("location");
        }
        return missingFields;
    }

    private List<RecommendationShopVo> recommend(Long userId,
                                                 RecommendationChatRequest request,
                                                 RecommendationCriteria criteria) {
        List<Shop> shops = shopService.lambdaQuery()
                .eq(Shop::getTypeId, criteria.getTypeId())
                .list();
        Set<Long> historyShopIds = queryHistoryShopIds(userId);
        return shops.stream()
                .map(shop -> toRecommendation(shop, criteria, request, historyShopIds.contains(shop.getId())))
                .filter(Objects::nonNull)
                .sorted(buildComparator(criteria))
                .limit(MAX_RECOMMENDATION_SIZE)
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

    private String buildReply(RecommendationCriteria criteria,
                              List<RecommendationShopVo> recommendations,
                              List<String> missingFields) {
        if (!missingFields.isEmpty()) {
            return "我已经理解你的需求了，还需要获取当前位置后才能筛选"
                    + criteria.getRadiusMeters() / 1000 + "公里内的店铺。请允许定位，或补充你所在的商圈。";
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
