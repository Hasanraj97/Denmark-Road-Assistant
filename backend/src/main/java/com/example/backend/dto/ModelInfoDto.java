package com.example.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * What the application is willing to say publicly about the trained model.
 *
 * <p>{@code datasetPath} and {@code modelPath} used to be part of this record and
 * were served by the public {@code GET /api/model-info}. They carried absolute
 * paths from the original developer's machine, of the form
 * {@code E:\sw8-maps\ml-service\...}, which told any anonymous caller how the
 * server's filesystem is laid out and were not even accurate any more. They have
 * been removed.</p>
 *
 * <p>Unknown fields coming back from the ML service are ignored rather than
 * causing a parse failure, so the two services can be upgraded independently.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelInfoDto(
        String modelName,
        int sampleCount,
        double accuracy,
        List<String> labels,
        Map<String, Double> featureImportances,
        String syntheticDisclaimer
) {
}
