package org.javaup.agent.llm;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Data
@Accessors(chain = true)
public class LlmChatOptions {

    private String model;

    private BigDecimal temperature;

    private Integer maxTokens;

    private JSONObject responseFormat;
}
