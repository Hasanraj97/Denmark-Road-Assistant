package com.example.backend.dto;

public record RouteFeaturesDto(
        double distanceKm,
        double estimatedDurationMin,
        double avgSpeedKmh,
        double roadTypeScore,
        double trafficLevel,
        double weatherRisk,
        double rainfallMm,
        String timeOfDay,
        String dayOfWeek,
        double urbanDensity,
        double accidentRiskScore,
        double constructionRiskScore,
        double elevationVariation,
        double temperatureC,
        double humidity,
        double windSpeedKmh,
        String season,
        String regionType,
        double vehicleLoadFactor
) {
}
