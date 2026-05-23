package org.javaup.agent.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class RecommendationCriteria implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long typeId;

    private String typeName;

    private Integer peopleCount;

    private Long budgetMin;

    private Long budgetMax;

    private String budgetPreference;

    private Long perCapitaBudgetMin;

    private Long perCapitaBudgetMax;

    private String distanceLevel;

    private Integer radiusMeters;

    private String startTime;

    private String endTime;

    private String sortBy;

    private Boolean needLocation;
}
