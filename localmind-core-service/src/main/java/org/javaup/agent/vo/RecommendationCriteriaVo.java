package org.javaup.agent.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class RecommendationCriteriaVo {

    private Long typeId;

    private String typeName;

    private Integer peopleCount;

    private Long budgetMin;

    private Long budgetMax;

    private Long perCapitaBudgetMin;

    private Long perCapitaBudgetMax;

    private Integer radiusMeters;

    private String startTime;

    private String endTime;

    private String sortBy;
}
