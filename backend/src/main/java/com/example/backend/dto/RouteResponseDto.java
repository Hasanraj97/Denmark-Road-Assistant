package com.example.backend.dto;

import java.util.List;

public record RouteResponseDto(
        String originLabel,
        String destinationLabel,
        double distanceKm,
        double durationMinutes,
        List<RoutePointDto> geometry
) {
}
