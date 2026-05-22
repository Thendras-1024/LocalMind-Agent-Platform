package org.javaup.agent.service;

import org.javaup.agent.config.LocalMindAiProperties;
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
        LocalMindAiProperties properties = new LocalMindAiProperties();
        properties.setMaxCandidates(2);
        properties.setMaxContextChars(500);
        RecommendationContextBuilder builder = new RecommendationContextBuilder(properties);

        List<LlmRecommendationCandidate> candidates = List.of(
                candidate(1L, 48, 1000D, false),
                candidate(2L, 47, 500D, true),
                candidate(3L, 49, 300D, false)
        );

        LlmRecommendationContext context = builder.build(candidates,
                new RecommendationCriteria().setSortBy("score"));

        assertEquals(3, context.getOriginalCandidateSize());
        assertEquals(2, context.getIncludedCandidateSize());
        assertTrue(context.getTruncated());
        assertEquals(2L, context.getCandidates().get(0).getShopId());
    }

    private LlmRecommendationCandidate candidate(Long id, Integer score, Double distance, boolean history) {
        RecommendationShopVo shop = new RecommendationShopVo()
                .setShopId(id)
                .setName("shop-" + id)
                .setScore(score / 10.0)
                .setAvgPrice(100L)
                .setDistance(distance)
                .setHasHistoryOrder(history)
                .setReason("reason");
        return new LlmRecommendationCandidate().setShop(shop);
    }
}
