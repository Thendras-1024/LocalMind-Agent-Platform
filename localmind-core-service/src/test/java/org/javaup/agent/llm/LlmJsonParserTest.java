package org.javaup.agent.llm;

import org.javaup.agent.model.LlmRecommendationOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmJsonParserTest {

    private final LlmJsonParser parser = new LlmJsonParser();

    @Test
    void parseObjectShouldReadPlainJson() {
        LlmRecommendationOutput output = parser.parseObject("""
                {"reply":"ok","needClarification":false,"missingFields":[],"recommendations":[{"shopId":1,"reason":"评分高"}]}
                """, LlmRecommendationOutput.class);

        assertEquals("ok", output.getReply());
        assertEquals(1L, output.getRecommendations().get(0).getShopId());
    }

    @Test
    void parseObjectShouldReadFencedJson() {
        LlmRecommendationOutput output = parser.parseObject("""
                ```json
                {"reply":"ok","needClarification":true,"missingFields":["location"],"recommendations":[]}
                ```
                """, LlmRecommendationOutput.class);

        assertEquals(true, output.getNeedClarification());
        assertEquals("location", output.getMissingFields().get(0));
    }

    @Test
    void parseObjectShouldRejectNonJson() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseObject("not-json", LlmRecommendationOutput.class));
    }
}
