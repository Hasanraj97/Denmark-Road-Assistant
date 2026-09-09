package com.example.backend.routing;

import com.example.backend.dto.RouteObservationDto;
import com.example.backend.dto.WeatherReadingDto;
import com.example.backend.weather.WeatherService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Gathers the raw facts about a trip so the ML service can derive the features.
 *
 * <p>This class replaces {@code FeatureEngineeringService}, which computed all
 * nineteen model features in Java. That class is gone, and with it four separate
 * defects:</p>
 *
 * <ul>
 *   <li><strong>F3</strong> - it read the departure <em>hour</em> from the user's
 *       chosen time but the <em>day of week</em> and <em>season</em> from
 *       {@code now()}, so a trip planned for Saturday morning could be scored
 *       with Tuesday's traffic pattern. There is now one resolved instant, and
 *       all three come from it.</li>
 *   <li><strong>M2</strong> - its formulas had drifted away from the ones used to
 *       generate the training data. There is now only one set of formulas, in
 *       Python, used for both.</li>
 *   <li><strong>M3</strong> - it had a function called {@code noise()} that was
 *       not random: it multiplied every feature by a factor derived from the
 *       number of characters in the two place names. Nothing like it remains.</li>
 *   <li><strong>M4</strong> - it invented the weather from the season. Real
 *       Open-Meteo values are now fetched and passed through.</li>
 * </ul>
 */
@Service
public class RouteObservationService {

    private static final ZoneId DENMARK = ZoneId.of("Europe/Copenhagen");

    /**
     * How many days ahead a trip may be planned, counting today as day one.
     *
     * <p>This is deliberately tied to how far Open-Meteo will forecast. The app
     * promises that the weather behind every grade is a real forecast, so it
     * refuses to answer for a day it cannot get one for, rather than quietly
     * falling back to a seasonal average. {@link WeatherService} requests the
     * same number of days.</p>
     */
    public static final int FORECAST_WINDOW_DAYS = 7;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final WeatherService weatherService;

    public RouteObservationService(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    public RouteObservationDto observe(RouteComputation route, String selectedDepartureDate,
                                       String selectedDepartureTime) {
        LocalDateTime departure = resolveDeparture(selectedDepartureDate, selectedDepartureTime);

        // The midpoint is a better single weather sample than either endpoint for
        // a trip that may cross the country.
        double midpointLat = (route.origin().latitude() + route.destination().latitude()) / 2.0;
        double midpointLng = (route.origin().longitude() + route.destination().longitude()) / 2.0;
        WeatherReadingDto weather = weatherService.forecastFor(midpointLat, midpointLng, departure);

        // Averaging the two endpoints, each scored separately. The old code
        // searched the two place names glued together, so an origin of Copenhagen
        // made the destination look like a city as well.
        double urbanSignal = (route.origin().urbanScore() + route.destination().urbanScore()) / 2.0;

        return new RouteObservationDto(
                round(route.distanceKm()),
                round(route.durationMinutes()),
                departure.toString(),
                round(urbanSignal),
                weather
        );
    }

    /**
     * Turns the day and time the user picked into one departure instant.
     *
     * <p>A missing date means today. A missing time means the current hour. The
     * result always has minutes, seconds and nanoseconds zeroed, because the
     * weather forecast and the model both work in whole hours.</p>
     *
     * @throws ResponseStatusException 400 if the date is in the past or beyond the
     *         forecast window. Answering for a day we cannot get a real forecast
     *         for would quietly break the app's central promise, so it is refused
     *         with a message that says what the allowed range is.
     */
    LocalDateTime resolveDeparture(String selectedDepartureDate, String selectedDepartureTime) {
        ZonedDateTime now = ZonedDateTime.now(DENMARK);
        LocalTime chosenTime = parseTime(selectedDepartureTime, now.toLocalTime());

        if (selectedDepartureDate == null || selectedDepartureDate.isBlank()) {
            return LocalDateTime.of(now.toLocalDate(), chosenTime).withMinute(0).withSecond(0).withNano(0);
        }

        LocalDate chosenDate;
        try {
            chosenDate = LocalDate.parse(selectedDepartureDate.trim(), DATE);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "\"" + selectedDepartureDate + "\" is not a date. Use the format 2026-09-15.");
        }

        LocalDate today = now.toLocalDate();
        LocalDate lastAllowed = today.plusDays(FORECAST_WINDOW_DAYS - 1L);

        if (chosenDate.isBefore(today)) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "That date has already passed. Pick a day between " + today + " and " + lastAllowed + ".");
        }

        if (chosenDate.isAfter(lastAllowed)) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "The weather forecast only reaches " + lastAllowed + ", and this app will not grade a "
                            + "route without a real forecast behind it. Pick a day between "
                            + today + " and " + lastAllowed + ".");
        }

        return LocalDateTime.of(chosenDate, chosenTime).withMinute(0).withSecond(0).withNano(0);
    }

    private LocalTime parseTime(String selectedDepartureTime, LocalTime fallback) {
        if (selectedDepartureTime == null || selectedDepartureTime.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(selectedDepartureTime.trim());
        } catch (DateTimeParseException exception) {
            return fallback;
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
