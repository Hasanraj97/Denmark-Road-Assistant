package com.example.backend.dto;

import java.time.Instant;

public record ApiErrorResponse(
        String message,
        Instant timestamp
) {
}
