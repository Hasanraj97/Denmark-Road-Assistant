package com.example.backend.weather;

import com.example.backend.config.AppProperties;
import com.example.backend.dto.WeatherReadingDto;
import com.example.backend.routing.RouteObservationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;

/**
 * Fetches the real hourly forecast that the prediction is based on.
 *
 * <p>This closes the most embarrassing gap in the original app. The frontend
 * already called Open-Meteo and showed a genuine forecast on screen - but the
 * model never saw it. The five weather features it actually used
 * ({@code weather_risk}, {@code rainfall_mm}, {@code humidity},
 * {@code wind_speed_kmh}, {@code temperature_c}) were all guessed from the season
 * alone, so a real forecast of driving rain sat directly beside a prediction
 * computed as though it were an average autumn day.</p>
 *
 * <p>The forecast is taken at the <em>midpoint</em> of the route, which is a
 * better single sample than either endpoint for a trip that may cross the
 * country, and at the hour the driver actually intends to leave.</p>
 *
 * <p>If Open-Meteo cannot be reached the request still succeeds: a seasonal
 * estimate is substituted and clearly marked as such, so the user is told which
 * one they are looking at rather than being silently misled.</p>
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private static final DateTimeFormatter HOUR_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00");

    private final RestClient restClient;
    private final AppProperties appProperties;

    public WeatherService(RestClient.Builder builder, AppProperties appProperties) {
        this.restClient = builder.build();
        this.appProperties = appProperties;
    }

    /**
     * Returns the weather for the given point at the given local hour, falling
     * back to a seasonal estimate rather than failing the whole request.
     */
    public WeatherReadingDto forecastFor(double latitude, double longitude, LocalDateTime departure) {
        try {
            URI uri = UriComponentsBuilder
                    .fromUriString(appProperties.weatherBaseUrl())
                    .queryParam("latitude", latitude)
                    .queryParam("longitude", longitude)
                    .queryParam("hourly",
                            "temperature_2m,precipitation,relative_humidity_2m,wind_speed_10m")
                    .queryParam("timezone", "Europe/Copenhagen")
                    // Must cover the same span RouteObservationService allows a
                    // user to pick, or a legal date could come back with no
                    // forecast and silently fall through to the seasonal estimate.
                    .queryParam("forecast_days", RouteObservationService.FORECAST_WINDOW_DAYS)
                    .build()
                    .toUri();

            JsonNode response = restClient.get().uri(uri).retrieve().body(JsonNode.class);
            WeatherReadingDto reading = readHour(response, departure);
            if (reading != null) {
                return reading;
            }
            log.warn("Open-Meteo returned no entry for {}; using a seasonal estimate.", departure);
        } catch (Exception ex) {
            // Deliberately not fatal. A weather outage should degrade the advisory,
            // not break the whole route analysis.
            log.warn("Open-Meteo request failed ({}); using a seasonal estimate.", ex.toString());
        }

        return seasonalEstimate(departure);
    }

    private WeatherReadingDto readHour(JsonNode response, LocalDateTime departure) {
        if (response == null) {
            return null;
        }

        JsonNode hourly = response.path("hourly");
        JsonNode times = hourly.path("time");
        if (!times.isArray() || times.isEmpty()) {
            return null;
        }

        String wanted = departure.format(HOUR_KEY);
        int index = -1;
        for (int i = 0; i < times.size(); i++) {
            if (wanted.equals(times.get(i).asText())) {
                index = i;
                break;
            }
        }

        if (index < 0) {
            return null;
        }

        JsonNode temperatures = hourly.path("temperature_2m");
        JsonNode precipitation = hourly.path("precipitation");
        JsonNode humidity = hourly.path("relative_humidity_2m");
        JsonNode wind = hourly.path("wind_speed_10m");

        if (temperatures.path(index).isMissingNode() || wind.path(index).isMissingNode()) {
            return null;
        }

        return new WeatherReadingDto(
                temperatures.path(index).asDouble(),
                Math.max(0.0, precipitation.path(index).asDouble(0.0)),
                // Open-Meteo reports relative humidity as a percentage; the model
                // works in 0-1.
                Math.min(1.0, Math.max(0.0, humidity.path(index).asDouble(75.0) / 100.0)),
                Math.max(0.0, wind.path(index).asDouble()),
                WeatherReadingDto.SOURCE_LIVE
        );
    }

    /**
     * The fallback. Long-run Danish seasonal averages - honest, unremarkable
     * numbers, and always labelled so nobody mistakes them for a forecast.
     */
    WeatherReadingDto seasonalEstimate(LocalDateTime departure) {
        Month month = departure.getMonth();
        return switch (month) {
            case DECEMBER, JANUARY, FEBRUARY ->
                    new WeatherReadingDto(2.0, 0.6, 0.87, 22.0, WeatherReadingDto.SOURCE_FALLBACK);
            case MARCH, APRIL, MAY ->
                    new WeatherReadingDto(8.5, 0.3, 0.74, 18.0, WeatherReadingDto.SOURCE_FALLBACK);
            case JUNE, JULY, AUGUST ->
                    new WeatherReadingDto(17.5, 0.3, 0.68, 15.0, WeatherReadingDto.SOURCE_FALLBACK);
            default ->
                    new WeatherReadingDto(9.5, 0.5, 0.83, 20.0, WeatherReadingDto.SOURCE_FALLBACK);
        };
    }
}
