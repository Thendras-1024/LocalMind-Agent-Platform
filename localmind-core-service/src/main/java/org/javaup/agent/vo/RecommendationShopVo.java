package org.javaup.agent.vo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class RecommendationShopVo {

    private Long shopId;

    private String name;

    private String area;

    private String address;

    private String image;

    private Double score;

    private Integer comments;

    private Long avgPrice;

    private Double distance;

    private Long estimatedTotalPrice;

    private Boolean hasHistoryOrder;

    private String reason;
}
