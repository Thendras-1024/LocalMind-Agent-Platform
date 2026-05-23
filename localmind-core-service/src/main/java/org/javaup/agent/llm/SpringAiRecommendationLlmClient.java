package org.javaup.agent.llm;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.javaup.agent.config.RecommendationAgentProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SpringAiRecommendationLlmClient implements RecommendationLlmClient {

    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;

    private final RecommendationAgentProperties properties;

    @Override
    public boolean isConfigured() {
        return Boolean.TRUE.equals(properties.getEnabled())
                && chatClientBuilderProvider.getIfAvailable() != null;
    }

    @Override
    public LlmChatResult chat(List<LlmChatMessage> messages, LlmChatOptions options) {
        ensureConfigured();
        ChatClient.Builder chatClientBuilder = chatClientBuilderProvider.getIfAvailable();
        if (chatClientBuilder == null) {
            throw new LlmClientException("Spring AI ChatClient is not configured", 0, false);
        }
        ChatResponse response = chatClientBuilder.build()
                .prompt()
                .messages(toSpringMessages(messages))
                .options(toOpenAiOptions(options))
                .call()
                .chatResponse();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new LlmClientException("LLM returned empty response", 200, true);
        }
        String content = response.getResult().getOutput().getText();
        if (StrUtil.isBlank(content)) {
            throw new LlmClientException("LLM returned empty content", 200, true);
        }
        return new LlmChatResult()
                .setContent(content)
                .setModel(response.getMetadata() == null ? null : response.getMetadata().getModel());
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new LlmClientException("LLM api key is not configured", 0, false);
        }
    }

    private List<Message> toSpringMessages(List<LlmChatMessage> messages) {
        List<Message> result = new ArrayList<>();
        for (LlmChatMessage message : messages) {
            if ("system".equalsIgnoreCase(message.getRole())) {
                result.add(new SystemMessage(message.getContent()));
            } else {
                result.add(new UserMessage(message.getContent()));
            }
        }
        return result;
    }

    private OpenAiChatOptions toOpenAiOptions(LlmChatOptions options) {
        LlmChatOptions source = options == null ? new LlmChatOptions() : options;
        ResponseFormat responseFormat = null;
        if (source.getResponseFormat() != null) {
            String type = source.getResponseFormat().getString("type");
            if ("json_object".equals(type)) {
                responseFormat = ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_OBJECT)
                        .build();
            } else if ("json_schema".equals(type) && Boolean.TRUE.equals(properties.getJsonSchemaEnabled())) {
                responseFormat = ResponseFormat.builder()
                        .type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(source.getResponseFormat().getJSONObject("json_schema").toJSONString())
                        .build();
            }
        }
        return OpenAiChatOptions.builder()
                .responseFormat(responseFormat)
                .build();
    }
}
