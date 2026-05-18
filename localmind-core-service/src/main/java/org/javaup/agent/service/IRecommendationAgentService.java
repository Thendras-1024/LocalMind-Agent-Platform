package org.javaup.agent.service;

import org.javaup.agent.dto.RecommendationChatRequest;
import org.javaup.agent.vo.RecommendationChatResponse;

public interface IRecommendationAgentService {

    RecommendationChatResponse chat(RecommendationChatRequest request);
}
