package org.javaup.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RecommendationChatRequest {

    private String sessionId;

    @NotBlank(message = "请输入导购需求")
    private String message;

    private Double x;

    private Double y;
}
