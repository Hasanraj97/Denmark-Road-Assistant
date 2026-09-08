package com.example.backend.dto;

import java.util.List;
import java.util.Map;

public record PredictionResponseDto(
        String roadConditionLabel,
        double confidence,
        int leaveEarlyMinutes,
        String advisory,
        List<FeatureInfluenceDto> influentialFeatures,
        Map<String, Double> classProbabilities,
        boolean syntheticPrediction
) {
}
