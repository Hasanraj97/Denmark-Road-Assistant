package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record RouteRequestDto(
        @NotBlank(message = "Origin is required") String origin,
        @NotBlank(message = "Destination is required") String destination,
        String selectedDepartureTime
) {
}
