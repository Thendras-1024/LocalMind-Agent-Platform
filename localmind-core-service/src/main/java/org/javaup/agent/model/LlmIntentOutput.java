package org.javaup.agent.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class LlmIntentOutput {

    private Boolean localLifeRecommendation = true;

    private Boolean safe = true;

    private String safetyReply;

    private RecommendationCriteria criteria;

    private List<String> missingFields = new ArrayList<>();
}
