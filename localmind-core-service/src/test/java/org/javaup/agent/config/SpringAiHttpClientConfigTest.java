package org.javaup.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiHttpClientConfigTest {

    @Test
    void springAiHttpObjectMapperKeepsNumbersAsNumbers() throws Exception {
        ObjectMapper objectMapper = new SpringAiHttpClientConfig().springAiHttpObjectMapper();

        String json = objectMapper.writeValueAsString(Map.of(
                "temperature", 0.2,
                "dimensions", 1024
        ));
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("temperature").isNumber()).isTrue();
        assertThat(root.get("dimensions").isNumber()).isTrue();
        assertThat(root.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(root.get("dimensions").asInt()).isEqualTo(1024);
    }
}
