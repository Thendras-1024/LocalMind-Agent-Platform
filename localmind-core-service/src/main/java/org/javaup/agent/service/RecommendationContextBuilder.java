package org.javaup.agent.service;

import com.alibaba.fastjson.JSON;
import org.javaup.agent.config.RecommendationAgentProperties;
import org.javaup.agent.model.LlmRecommendationCandidate;
import org.javaup.agent.model.LlmRecommendationContext;
import org.javaup.agent.model.RecommendationCriteria;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class RecommendationContextBuilder {

    private final RecommendationAgentProperties properties;

    public RecommendationContextBuilder(RecommendationAgentProperties properties) {
        this.properties = properties;
    }

    public LlmRecommendationContext build(List<LlmRecommendationCandidate> candidates,
                                          RecommendationCriteria criteria) {
        List<LlmRecommendationCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(buildComparator(criteria));

        LlmRecommendationContext context = new LlmRecommendationContext()
                .setOriginalCandidateSize(candidates.size());
        for (LlmRecommendationCandidate candidate : sorted) {
            if (context.getCandidates().size() >= properties.getMaxCandidates()) {
                context.setTruncated(true);
                break;
            }
            context.getCandidates().add(candidate);
            int chars = JSON.toJSONString(context.getCandidates()).length();
            if (chars > properties.getMaxContextChars()) {
                context.getCandidates().remove(context.getCandidates().size() - 1);
                context.setTruncated(true);
                break;
            }
            context.setContextChars(chars);
        }
        context.setIncludedCandidateSize(context.getCandidates().size());
        return context;
    }

    private Comparator<LlmRecommendationCandidate> buildComparator(RecommendationCriteria criteria) {
        Comparator<LlmRecommendationCandidate> comparator;
        if ("distance".equals(criteria.getSortBy())) {
            comparator = Comparator.comparing(item -> item.getShop().getDistance() == null
                    ? Double.MAX_VALUE : item.getShop().getDistance());
        } else if ("price".equals(criteria.getSortBy())) {
            comparator = Comparator.comparing(item -> item.getShop().getAvgPrice() == null
                    ? Long.MAX_VALUE : item.getShop().getAvgPrice());
        } else {
            comparator = Comparator.comparing(
                    item -> item.getShop().getRankScore(),
                    Comparator.nullsLast(Comparator.reverseOrder())
            );
        }
        return comparator
                .thenComparing(item -> item.getShop().getDistance() == null ? Double.MAX_VALUE : item.getShop().getDistance())
                .thenComparing(item -> item.getShop().getAvgPrice() == null ? Long.MAX_VALUE : item.getShop().getAvgPrice());
    }
}
