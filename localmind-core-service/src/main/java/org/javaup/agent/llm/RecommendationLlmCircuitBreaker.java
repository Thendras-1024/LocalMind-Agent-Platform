package org.javaup.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.javaup.agent.config.RecommendationAgentProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RecommendationLlmCircuitBreaker {

    private static final String OPEN_SUFFIX = ":open";
    private static final String FAILURE_SUFFIX = ":failures";

    private final RecommendationAgentProperties properties;

    private final StringRedisTemplate stringRedisTemplate;

    public RecommendationLlmCircuitBreaker(RecommendationAgentProperties properties, StringRedisTemplate stringRedisTemplate) {
        this.properties = properties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean isOpen() {
        Boolean exists = stringRedisTemplate.hasKey(openKey());
        return Boolean.TRUE.equals(exists);
    }

    public void recordSuccess() {
        stringRedisTemplate.delete(failureKey());
    }

    public void recordFailure(String reason) {
        Long failures = stringRedisTemplate.opsForValue().increment(failureKey());
        if (failures != null && failures == 1L) {
            stringRedisTemplate.expire(failureKey(), properties.getCircuitBreaker().getFailureWindowSeconds(), TimeUnit.SECONDS);
        }
        if (failures != null && failures >= properties.getCircuitBreaker().getFailureThreshold()) {
            stringRedisTemplate.opsForValue().set(openKey(), reason == null ? "open" : reason,
                    properties.getCircuitBreaker().getOpenSeconds(), TimeUnit.SECONDS);
            log.warn("Recommendation LLM circuit opened, failures={}, reason={}", failures, reason);
        }
    }

    private String openKey() {
        return properties.getCircuitBreaker().getKeyPrefix() + OPEN_SUFFIX;
    }

    private String failureKey() {
        return properties.getCircuitBreaker().getKeyPrefix() + FAILURE_SUFFIX;
    }
}
