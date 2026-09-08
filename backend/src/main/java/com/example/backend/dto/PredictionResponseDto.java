package com.example.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * What the ML service says about a route.
 *
 * <p>{@code features} is new. Now that the ML service derives the feature vector
 * rather than receiving it, it has to send the vector back so the app can show
 * the user what the model actually looked at.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PredictionResponseDto(
        String roadConditionLabel,
        double confidence,
        int leaveEarlyMinutes,
        String advisory,
        List<FeatureInfluenceDto> influentialFeatures,
        Map<String, Double> classProbabilities,
        RouteFeaturesDto features,
        boolean syntheticPrediction,
        String disclaimer
) {
}
