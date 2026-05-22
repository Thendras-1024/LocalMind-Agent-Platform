package org.javaup.agent.llm;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LlmChatResult {

    private String content;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;
}
