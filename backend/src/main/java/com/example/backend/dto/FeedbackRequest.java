package com.example.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
        @NotNull(message = "Helpful selection is required") Boolean helpful,
        @Size(max = 1000, message = "Comment must be 1000 characters or fewer") String comment
) {
}
