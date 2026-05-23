package org.javaup.agent.model;

import lombok.Data;
import lombok.experimental.Accessors;
import org.javaup.agent.vo.RecommendationShopVo;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class LlmRecommendationCandidate implements Serializable {

    private static final long serialVersionUID = 1L;

    private RecommendationShopVo shop;

    public Long getShopId() {
        return shop == null ? null : shop.getShopId();
    }
}
