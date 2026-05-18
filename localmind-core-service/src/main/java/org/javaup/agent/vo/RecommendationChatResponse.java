package org.javaup.agent.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class RecommendationChatResponse {

    private String sessionId;

    private String reply;

    private RecommendationCriteriaVo criteria;

    private List<RecommendationShopVo> recommendations;

    private Boolean needClarification;

    private List<String> missingFields;
}
