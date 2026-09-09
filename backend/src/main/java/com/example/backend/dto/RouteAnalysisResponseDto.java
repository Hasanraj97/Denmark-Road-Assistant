package com.example.backend.dto;

/**
 * Everything the app needs to draw one analysis.
 *
 * @param incidents    real events Vejdirektoratet reports on this route. Shown to
 *                     the user, deliberately NOT fed to the model - see
 *                     RouteIncidentDto for why.
 * @param weather      the weather actually used by the model, including whether
 *                     it was a live Open-Meteo forecast or a seasonal fallback.
 *                     The UI shows this so the user is never misled about which.
 * @param syntheticModel true because the road-condition grade comes from a model
 *                     trained on generated data. Note this is about the MODEL, not
 *                     the weather - the field used to be called syntheticDemo and
 *                     the UI wording it drove wrongly implied the weather was
 *                     invented too.
 */
public record RouteAnalysisResponseDto(
        RouteResponseDto route,
        RouteFeaturesDto features,
        PredictionResponseDto prediction,
        WeatherReadingDto weather,
        java.util.List<RouteIncidentDto> incidents,
        Long historyId,
        boolean syntheticModel,
        String disclaimer
) {
}
