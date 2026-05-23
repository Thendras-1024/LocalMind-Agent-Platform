package org.javaup.agent.llm;

import java.util.List;

public interface RecommendationLlmClient {

    boolean isConfigured();

    LlmChatResult chat(List<LlmChatMessage> messages, LlmChatOptions options);
}
