package com.example.backend.dto;

import java.util.List;
import java.util.Map;

public record ModelInfoDto(
        String modelName,
        String datasetPath,
        String modelPath,
        int sampleCount,
        double accuracy,
        List<String> labels,
        Map<String, Double> featureImportances,
        String syntheticDisclaimer
) {
}
