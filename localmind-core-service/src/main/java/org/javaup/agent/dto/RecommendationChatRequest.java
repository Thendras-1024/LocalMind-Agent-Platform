package org.javaup.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.io.Serializable;


@Data
public class RecommendationChatRequest implements Serializable{

    private String sessionId;

    @NotBlank(message = "请输入导购需求")
    private String message;

    private Double x;

    private Double y;
}
