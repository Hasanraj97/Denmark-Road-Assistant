package com.example.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The nineteen features the model reads.
 *
 * <p>The backend no longer computes these - the ML service derives them and
 * sends them back, so this record is now a description of what came back rather
 * than a specification of what to build. Unknown fields are ignored so the two
 * services can be upgraded independently.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
