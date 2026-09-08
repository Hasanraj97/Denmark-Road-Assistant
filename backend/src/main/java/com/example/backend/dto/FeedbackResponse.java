package com.example.backend.dto;

import java.time.LocalDateTime;

public record FeedbackResponse(
        Long id,
        Long historyId,
        boolean helpful,
        String comment,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
