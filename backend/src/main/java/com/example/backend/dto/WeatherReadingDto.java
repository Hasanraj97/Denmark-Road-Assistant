package com.example.backend.dto;

/**
 * The weather actually used to grade a route.
 *
 * @param temperatureC decides whether ice is possible
 * @param rainfallMm   millimetres in the departure hour
 * @param humidity     0 to 1, used as a stand-in for mist and fog
 * @param windSpeedKmh matters on exposed stretches and bridges
 * @param source       {@code "open-meteo"} for a live forecast, or
 *                     {@code "seasonal-estimate"} when Open-Meteo could not be
 *                     reached. The UI shows this, so the user always knows
 *                     whether they are looking at a real forecast or a fallback.
 */
public record WeatherReadingDto(
        double temperatureC,
        double rainfallMm,
        double humidity,
        double windSpeedKmh,
        String source
) {
    public static final String SOURCE_LIVE = "open-meteo";
    public static final String SOURCE_FALLBACK = "seasonal-estimate";

    public boolean isLive() {
        return SOURCE_LIVE.equals(source);
    }
}
