package com.example.backend.routing;

import com.example.backend.dto.RouteObservationDto;
import com.example.backend.dto.RoutePointDto;
import com.example.backend.dto.WeatherReadingDto;
import com.example.backend.weather.WeatherService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the class that turns a computed route plus a picked time into
 * the raw observation the ML service derives its features from.
 *
 * <p>Two behaviours are worth pinning here.</p>
 *
 * <p><strong>Departure resolution (defect F3).</strong> The chosen clock time
 * means its next occurrence: today if it has not passed, tomorrow if it has.
 * Getting this wrong is not cosmetic - the single resolved instant supplies the
 * hour, the weekday <em>and</em> the season to the model, so a trip planned for
 * tomorrow morning used to be scored with today's traffic pattern.</p>
 *
 * <p><strong>Urban signal.</strong> It is the MEAN of the two endpoints' own
 * scores. The version this replaced glued the two place names into one string
 * and searched that, so an origin of Copenhagen made the destination look like a
 * city too and the two endpoints could not be told apart.</p>
 *
 * <p>{@link WeatherService} is mocked, so nothing here reaches Open-Meteo.</p>
 */
class RouteObservationServiceTest {

    private static final ZoneId DENMARK = ZoneId.of("Europe/Copenhagen");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final WeatherService weatherService = mock(WeatherService.class);
    private final RouteObservationService service = new RouteObservationService(weatherService);

    private static GeocodedLocation place(String name, double lat, double lng, double urbanScore) {
        return new GeocodedLocation(name, lat, lng, urbanScore);
    }

    private static RouteComputation routeBetween(GeocodedLocation origin, GeocodedLocation destination) {
        return new RouteComputation(
                origin,
                destination,
                123.456,
                45.678,
                List.of(new RoutePointDto(origin.latitude(), origin.longitude()),
                        new RoutePointDto(destination.latitude(), destination.longitude()))
        );
    }

    // ------------------------------------------------------------------
    // resolveDeparture
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a time still ahead today resolves to today")
    void timeLaterTodayResolvesToToday() {
        ZonedDateTime now = ZonedDateTime.now(DENMARK);
        Assumptions.assumeFalse(now.getHour() == 23 && now.getMinute() >= 57,
                "skipped in the last three minutes of the day, where 'later today' does not exist");

        LocalTime chosen = now.toLocalTime().plusMinutes(2).withSecond(0).withNano(0);

        LocalDateTime resolved = service.resolveDeparture(chosen.format(HH_MM));

        assertThat(resolved.toLocalDate()).isEqualTo(now.toLocalDate());
        assertThat(resolved.getHour()).isEqualTo(chosen.getHour());
    }

    @Test
    @DisplayName("a time that has already passed resolves to tomorrow")
    void timeAlreadyPastResolvesToTomorrow() {
        ZonedDateTime now = ZonedDateTime.now(DENMARK);
        Assumptions.assumeFalse(now.getHour() == 0 && now.getMinute() <= 2,
                "skipped in the first three minutes of the day, where 'earlier today' wraps to yesterday");

        LocalTime chosen = now.toLocalTime().minusMinutes(2).withSecond(0).withNano(0);

        LocalDateTime resolved = service.resolveDeparture(chosen.format(HH_MM));

        assertThat(resolved.toLocalDate())
                .as("a time that is already behind us means the same time tomorrow")
                .isEqualTo(now.toLocalDate().plusDays(1));
        assertThat(resolved.getHour()).isEqualTo(chosen.getHour());
    }

    @ParameterizedTest(name = "\"{0}\" falls back to the current hour")
    @ValueSource(strings = {"", "   ", "not a time", "25:00", "8", "half past eight", "tomorrow morning"})
    @DisplayName("blank or unparseable input falls back to the current hour instead of failing")
    void unusableInputFallsBackToNow(String input) {
        LocalDateTime before = LocalDateTime.now(DENMARK).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime resolved = service.resolveDeparture(input);
        LocalDateTime after = LocalDateTime.now(DENMARK).truncatedTo(ChronoUnit.HOURS);

        assertThat(resolved).isBetween(before, after);
        assertThat(resolved.getMinute()).isZero();
    }

    @Test
    @DisplayName("null input falls back to the current hour instead of throwing")
    void nullInputFallsBackToNow() {
        LocalDateTime before = LocalDateTime.now(DENMARK).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime resolved = service.resolveDeparture(null);
        LocalDateTime after = LocalDateTime.now(DENMARK).truncatedTo(ChronoUnit.HOURS);

        assertThat(resolved).isBetween(before, after);
    }

    @ParameterizedTest(name = "\"{0}\" keeps its hour and zeroes the minutes")
    @ValueSource(strings = {"00:17", "06:45", "12:34", "17:59", "23:31"})
    @DisplayName("the resolved instant always has minutes, seconds and nanos zeroed")
    void minutesAreAlwaysZeroed(String input) {
        LocalDateTime resolved = service.resolveDeparture(input);

        assertThat(resolved.getMinute()).as("minutes must be zeroed").isZero();
        assertThat(resolved.getSecond()).isZero();
        assertThat(resolved.getNano()).isZero();
        assertThat(resolved.getHour()).isEqualTo(LocalTime.parse(input).getHour());

        LocalDate today = LocalDate.now(DENMARK);
        assertThat(resolved.toLocalDate()).isIn(today, today.plusDays(1));
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated")
    void whitespaceIsTrimmed() {
        LocalDateTime resolved = service.resolveDeparture("  09:30  ");
        assertThat(resolved.getHour()).isEqualTo(9);
        assertThat(resolved.getMinute()).isZero();
    }

    // ------------------------------------------------------------------
    // observe
    // ------------------------------------------------------------------

    @Test
    @DisplayName("urbanSignal is the MEAN of the two endpoints' own urban scores")
    void urbanSignalIsTheMeanOfBothEndpoints() {
        when(weatherService.forecastFor(anyDouble(), anyDouble(), any())).thenReturn(reading());

        RouteComputation route = routeBetween(
                place("København", 55.68, 12.57, 0.92),
                place("Tinglev", 54.93, 9.25, 0.30)
        );

        RouteObservationDto observation = service.observe(route, "08:00");

        assertThat(observation.urbanSignal())
                .as("(0.92 + 0.30) / 2")
                .isEqualTo(0.61, within(1e-9));
        assertThat(observation.urbanSignal())
                .as("it must not simply take one endpoint's score")
                .isNotEqualTo(0.92)
                .isNotEqualTo(0.30);
    }

    @Test
    @DisplayName("two rural endpoints stay rural - the city origin cannot bleed across")
    void ruralPairStaysRural() {
        when(weatherService.forecastFor(anyDouble(), anyDouble(), any())).thenReturn(reading());

        RouteObservationDto observation = service.observe(
                routeBetween(place("Skjern", 55.95, 8.50, 0.20), place("Nørre Snede", 55.96, 9.44, 0.10)),
                "08:00");

        assertThat(observation.urbanSignal()).isEqualTo(0.15, within(1e-9));
    }

    @Test
    @DisplayName("the weather is sampled at the midpoint of the route, at the departure hour")
    void weatherIsSampledAtTheMidpoint() {
        when(weatherService.forecastFor(anyDouble(), anyDouble(), any())).thenReturn(reading());

        RouteComputation route = routeBetween(
                place("A", 55.00, 10.00, 0.50),
                place("B", 57.00, 12.00, 0.50)
        );

        RouteObservationDto observation = service.observe(route, "08:00");

        ArgumentCaptor<Double> lat = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> lng = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<LocalDateTime> departure = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(weatherService).forecastFor(lat.capture(), lng.capture(), departure.capture());

        assertThat(lat.getValue()).isEqualTo(56.00, within(1e-9));
        assertThat(lng.getValue()).isEqualTo(11.00, within(1e-9));
        assertThat(departure.getValue().getHour()).isEqualTo(8);
        assertThat(departure.getValue().getMinute()).isZero();

        assertThat(observation.weather()).isEqualTo(reading());
        assertThat(observation.departureIso()).isEqualTo(departure.getValue().toString());
    }

    @Test
    @DisplayName("distance and duration are passed through, rounded to two decimals")
    void distanceAndDurationAreRounded() {
        when(weatherService.forecastFor(anyDouble(), anyDouble(), any())).thenReturn(reading());

        RouteObservationDto observation = service.observe(
                routeBetween(place("A", 55.0, 10.0, 0.4), place("B", 56.0, 11.0, 0.6)),
                "08:00");

        assertThat(observation.distanceKm()).isEqualTo(123.46);
        assertThat(observation.durationMin()).isEqualTo(45.68);
        assertThat(observation.urbanSignal()).isEqualTo(0.5, within(1e-9));
    }

    private static WeatherReadingDto reading() {
        return new WeatherReadingDto(4.5, 1.2, 0.9, 25.0, WeatherReadingDto.SOURCE_LIVE);
    }
}
