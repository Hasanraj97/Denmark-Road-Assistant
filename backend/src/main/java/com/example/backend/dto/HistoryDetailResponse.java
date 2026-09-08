package com.example.backend.dto;

import java.time.LocalDateTime;

public record HistoryDetailResponse(
        Long id,
        String originLabel,
        String destinationLabel,
        double distanceKm,
        double durationMinutes,
        String selectedDepartureTime,
        String predictedCondition,
        double confidence,
        int leaveEarlyMinutes,
        String advisoryText,
        double trafficLevel,
        double weatherRisk,
        double accidentRisk,
        double constructionRisk,
        double vehicleLoadFactor,
        String routeSummary,
        String featureSnapshot,
        LocalDateTime createdAt,
        FeedbackResponse feedback
) {
}
