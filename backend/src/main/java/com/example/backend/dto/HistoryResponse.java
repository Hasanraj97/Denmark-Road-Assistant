package com.example.backend.dto;

import java.time.LocalDateTime;

public record HistoryResponse(
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
        LocalDateTime createdAt,
        FeedbackResponse feedback
) {
}
