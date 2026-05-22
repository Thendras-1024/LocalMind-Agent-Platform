package org.javaup.agent.llm;

import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Component;

@Component
public class LlmJsonParser {

    public <T> T parseObject(String content, Class<T> type) {
        return JSON.parseObject(extractJsonObject(content), type);
    }

    public String extractJsonObject(String content) {
        if (content == null) {
            throw new IllegalArgumentException("empty llm content");
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("llm content is not json object");
        }
        return text.substring(start, end + 1);
    }
}
