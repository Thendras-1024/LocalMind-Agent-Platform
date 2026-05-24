package org.javaup.agent.service;

import org.javaup.agent.dto.RecommendationChatRequest;
import org.javaup.agent.vo.RecommendationChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IRecommendationAgentService {

    RecommendationChatResponse chat(RecommendationChatRequest request);

    SseEmitter streamChat(RecommendationChatRequest request);
}
