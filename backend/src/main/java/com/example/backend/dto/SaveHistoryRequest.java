package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveHistoryRequest(
        @NotBlank(message = "Origin label is required") @Size(max = 255) String originLabel,
        @NotBlank(message = "Destination label is required") @Size(max = 255) String destinationLabel,
        double distanceKm,
        double durationMinutes,
        @Size(max = 10) String selectedDepartureTime,
        @NotBlank(message = "Predicted condition is required") @Size(max = 40) String predictedCondition,
        double confidence,
        int leaveEarlyMinutes,
        @NotBlank(message = "Advisory text is required") String advisoryText,
        double trafficLevel,
        double weatherRisk,
        double accidentRisk,
        double constructionRisk,
        double vehicleLoadFactor,
        @Size(max = 255) String routeSummary,
        String featureSnapshot
) {
}
