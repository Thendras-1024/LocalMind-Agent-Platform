package org.javaup.agent.llm;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LlmChatOptions {

    private JSONObject responseFormat;
}
