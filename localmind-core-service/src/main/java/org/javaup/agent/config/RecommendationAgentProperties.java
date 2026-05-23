package org.javaup.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "localmind.recommendation")
public class RecommendationAgentProperties {

    private Boolean enabled = true;

    private Integer maxMessageChars = 500;

    private Integer maxSessionIdChars = 80;

    private Integer maxCandidates = 20;

    private Integer maxRecommendationSize = 3;

    private Integer maxContextChars = 6000;

    private Integer maxVouchersPerShop = 2;

    private Boolean fallbackEnabled = true;

    private Boolean jsonSchemaEnabled = false;

    private Boolean ruleMatchingEnabled = false;

    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    private RateLimit rateLimit = new RateLimit();

    @Data
    public static class CircuitBreaker {

        private String keyPrefix = "agent:recommendation:llm:circuit";

        private Integer failureThreshold = 3;

        private Integer failureWindowSeconds = 60;

        private Integer openSeconds = 60;
    }

    @Data
    public static class RateLimit {

        private Boolean enabled = true;

        private String keyPrefix = "agent:recommendation:rate";

        private Integer windowSeconds = 60;

        private Integer maxRequests = 20;
    }
}
