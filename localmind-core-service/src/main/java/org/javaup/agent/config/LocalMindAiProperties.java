package org.javaup.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Data
@Component
@ConfigurationProperties(prefix = "localmind.ai")
public class LocalMindAiProperties {

    private Boolean enabled = true;

    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private String apiKey = "";

    private Integer timeoutMs = 5000;

    private Integer maxMessageChars = 500;

    private Integer maxSessionIdChars = 80;

    private Integer maxCandidates = 20;

    private Integer maxRecommendationSize = 5;

    private Integer maxContextChars = 6000;

    private Integer maxVouchersPerShop = 2;

    private Boolean fallbackEnabled = true;

    private Boolean jsonSchemaEnabled = true;

    private Chat chat = new Chat();

    private Embedding embedding = new Embedding();

    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    private RateLimit rateLimit = new RateLimit();

    @Data
    public static class Chat {

        private String model = "qwen-plus";

        private BigDecimal temperature = BigDecimal.valueOf(0.2);

        private Integer maxTokens = 1200;
    }

    @Data
    public static class Embedding {

        private String model = "text-embedding-v4";

        private Integer dimensions = 1024;
    }

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
