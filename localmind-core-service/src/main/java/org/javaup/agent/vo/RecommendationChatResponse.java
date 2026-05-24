package org.javaup.agent.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

@Data
@Accessors(chain = true)
public class RecommendationChatResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sessionId;

    private String reply;

    private RecommendationCriteriaVo criteria;

    private List<RecommendationShopVo> recommendations;

    private Integer matchedShopCount;

    private Boolean needClarification;

    private List<String> missingFields;
}
