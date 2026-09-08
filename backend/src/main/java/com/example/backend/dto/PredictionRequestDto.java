package com.example.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record PredictionRequestDto(
        @Valid @NotNull RouteFeaturesDto features
) {
}
