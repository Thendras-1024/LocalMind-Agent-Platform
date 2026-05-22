package org.javaup.agent.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class LlmRecommendationContext {

    private List<LlmRecommendationCandidate> candidates = new ArrayList<>();

    private Integer originalCandidateSize = 0;

    private Integer includedCandidateSize = 0;

    private Integer contextChars = 0;

    private Boolean truncated = false;
}
