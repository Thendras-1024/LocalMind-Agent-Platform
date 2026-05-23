package org.javaup.agent.service;

import org.javaup.agent.config.RecommendationAgentProperties;
import org.javaup.agent.model.LlmRecommendationCandidate;
import org.javaup.agent.model.LlmRecommendationContext;
import org.javaup.agent.model.RecommendationCriteria;
import org.javaup.agent.vo.RecommendationShopVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationContextBuilderTest {

    @Test
    void buildShouldTrimByCandidateLimitAndContextChars() {
        RecommendationAgentProperties properties = new RecommendationAgentProperties();
        properties.setMaxCandidates(2);
        properties.setMaxContextChars(500);
        RecommendationContextBuilder builder = new RecommendationContextBuilder(properties);

        List<LlmRecommendationCandidate> candidates = List.of(
                candidate(1L, 48, 1000D, false),
                candidate(2L, 47, 500D, true),
                candidate(3L, 49, 300D, false)
        );

        LlmRecommendationContext context = builder.build(candidates,
                new RecommendationCriteria().setSortBy("compositeScore"));

        assertEquals(3, context.getOriginalCandidateSize());
        assertEquals(2, context.getIncludedCandidateSize());
        assertTrue(context.getTruncated());
        assertEquals(3L, context.getCandidates().get(0).getShopId());
    }

    private LlmRecommendationCandidate candidate(Long id, Integer score, Double distance, boolean history) {
        RecommendationShopVo shop = new RecommendationShopVo()
                .setShopId(id)
                .setName("shop-" + id)
                .setScore(score / 10.0)
                .setAvgPrice(100L)
                .setDistance(distance)
                .setRankScore(score / 10.0)
                .setHasHistoryOrder(history)
                .setReason("reason");
        return new LlmRecommendationCandidate().setShop(shop);
    }
}
