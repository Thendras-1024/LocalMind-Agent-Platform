package org.javaup.agent.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class LlmRecommendationOutput {

    private String reply;

    private Boolean needClarification = false;

    private List<String> missingFields = new ArrayList<>();

    private List<SelectedShop> recommendations = new ArrayList<>();

    @Data
    @Accessors(chain = true)
    public static class SelectedShop {

        private Long shopId;

        private String reason;
    }
}
