package com.example.backend.dto;

public record RouteAnalysisResponseDto(
        RouteResponseDto route,
        RouteFeaturesDto features,
        PredictionResponseDto prediction,
        Long historyId,
        boolean syntheticDemo,
        String disclaimer
) {
}
