package org.javaup.agent;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.javaup.agent.dto.RecommendationChatRequest;
import org.javaup.agent.service.IRecommendationAgentService;
import org.javaup.agent.vo.RecommendationChatResponse;
import org.javaup.dto.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/agent/recommendation")
public class RecommendationAgentController {

    @Resource
    private IRecommendationAgentService recommendationAgentService;

    @PostMapping("/chat")
    public Result<RecommendationChatResponse> chat(@Valid @RequestBody RecommendationChatRequest request) {
        return Result.ok(recommendationAgentService.chat(request));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody RecommendationChatRequest request) {
        return recommendationAgentService.streamChat(request);
    }
}
