package org.javaup.agent.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class LlmRecommendationOutput implements Serializable {

    private static final long serialVersionUID = 1L;

    private String reply;

    private Boolean needClarification = false;

    private List<String> missingFields = new ArrayList<>();

    private List<SelectedShop> recommendations = new ArrayList<>();

    @Data
    @Accessors(chain = true)
    public static class SelectedShop implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long shopId;

        private String reason;
    }
}
