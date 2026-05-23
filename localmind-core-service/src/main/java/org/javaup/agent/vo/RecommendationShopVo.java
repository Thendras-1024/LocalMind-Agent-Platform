package org.javaup.agent.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class RecommendationShopVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long shopId;

    private String name;

    private String typeName;

    private String area;

    private String address;

    private String image;

    private Double score;

    private Integer comments;

    private Long avgPrice;

    private Double distance;

    private Long estimatedTotalPrice;

    private Double compositeScore;

    private Double rankScore;

    private Boolean hasHistoryOrder;

    private String reason;
}
