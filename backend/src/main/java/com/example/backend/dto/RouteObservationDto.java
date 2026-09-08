package com.example.backend.dto;

/**
 * The raw facts about one trip, sent to the ML service.
 *
 * <p>This is the heart of the train/serve fix. The backend used to compute all
 * nineteen model features itself, in Java, using formulas that had drifted away
 * from the ones the Python service used to build its training data. Measured on
 * the shipped code, live vectors covered only about 8% of the training
 * distribution, so the model was extrapolating on nearly every real request.</p>
 *
 * <p>The backend now sends only what it genuinely knows and lets the ML service
 * derive the features with the exact function it used during training. There is
 * no second copy of the formulas left to drift.</p>
 *
 * @param distanceKm   road distance from OSRM
 * @param durationMin  driving time from OSRM
 * @param departureIso the single resolved departure instant. One timestamp
 *                     supplies the hour, the weekday and the season. The old code
 *                     took the hour from the user's choice but the day and season
 *                     from {@code now()}, so a trip planned for Saturday could be
 *                     scored with Tuesday's traffic pattern.
 * @param urbanSignal  how built-up the two endpoints are, averaged, 0 to 1
 * @param weather      the real Open-Meteo reading for the route midpoint
 */
public record RouteObservationDto(
        double distanceKm,
        double durationMin,
        String departureIso,
        double urbanSignal,
        WeatherReadingDto weather
) {
}
