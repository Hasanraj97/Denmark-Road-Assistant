package com.example.backend.routing;

import com.example.backend.dto.RouteObservationDto;
import com.example.backend.dto.WeatherReadingDto;
import com.example.backend.weather.WeatherService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

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

    private final WeatherService weatherService;

    public RouteObservationService(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    public RouteObservationDto observe(RouteComputation route, String selectedDepartureTime) {
        LocalDateTime departure = resolveDeparture(selectedDepartureTime);

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
     * Turns the time the user picked into a full date and time.
     *
     * <p>The chosen time is taken to mean its next occurrence: today if it has
     * not passed yet, otherwise tomorrow. Somebody setting "07:00" at nine in the
     * evening means tomorrow morning, and that also keeps the request inside the
     * window Open-Meteo can forecast.</p>
     */
    LocalDateTime resolveDeparture(String selectedDepartureTime) {
        ZonedDateTime now = ZonedDateTime.now(DENMARK);

        if (selectedDepartureTime == null || selectedDepartureTime.isBlank()) {
            return now.toLocalDateTime().withMinute(0).withSecond(0).withNano(0);
        }

        LocalTime chosen;
        try {
            chosen = LocalTime.parse(selectedDepartureTime.trim());
        } catch (DateTimeParseException exception) {
            return now.toLocalDateTime().withMinute(0).withSecond(0).withNano(0);
        }

        LocalDate date = now.toLocalDate();
        if (chosen.isBefore(now.toLocalTime())) {
            date = date.plusDays(1);
        }

        return LocalDateTime.of(date, chosen).withMinute(0).withSecond(0).withNano(0);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
