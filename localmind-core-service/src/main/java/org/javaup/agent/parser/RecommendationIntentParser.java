package org.javaup.agent.parser;

import org.javaup.agent.model.RecommendationCriteria;

public interface RecommendationIntentParser {

    RecommendationCriteria parse(String message);
}
