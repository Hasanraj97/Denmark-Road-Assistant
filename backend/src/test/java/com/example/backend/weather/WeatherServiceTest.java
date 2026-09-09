package com.example.backend.weather;

import com.example.backend.config.AppProperties;
import com.example.backend.dto.WeatherReadingDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The weather fallback, tested from inside the package because
 * {@code seasonalEstimate} is package-private on purpose.
 *
 * <p>A weather outage must degrade the advisory, not break the whole route
 * analysis - and the user must always be told which of the two they are looking
 * at. That is what {@code source} is for: {@code "open-meteo"} means a real
 * forecast, {@code "seasonal-estimate"} means the long-run average stood in.</p>
 *
 * <p>Nothing here touches the network: the HTTP calls are served by
 * {@link MockRestServiceServer}, and {@code seasonalEstimate} makes no call at
 * all.</p>
 */
class WeatherServiceTest {

    private static final String BASE_URL = "https://weather.invalid/v1/forecast";

    private static AppProperties properties() {
        return new AppProperties(
                "http://localhost:4200",
                "http://localhost:8000",
                56.2639,
                9.5018,
                "dk",
                "test-secret-that-is-long-enough-for-hs256!!",
                86_400_000L,
                BASE_URL,
                8,
                // Unreachable on purpose: nothing in this test should touch the
                // live Vejdirektoratet feed.
                "https://traffic.invalid/events.json"
        );
    }

    // ------------------------------------------------------------------
    // seasonalEstimate - the fallback itself
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} produces a labelled, sane seasonal estimate")
    @EnumSource(Month.class)
    @DisplayName("every month yields a seasonal estimate marked as such, with believable Danish values")
    void seasonalEstimateIsLabelledAndSaneForEveryMonth(Month month) {
        WeatherService service = new WeatherService(RestClient.builder(), properties());

        WeatherReadingDto reading = service.seasonalEstimate(LocalDateTime.of(2026, month, 15, 8, 0));

        assertThat(reading.source())
                .as("the user must be able to tell a fallback from a forecast")
                .isEqualTo(WeatherReadingDto.SOURCE_FALLBACK)
                .isEqualTo("seasonal-estimate");
        assertThat(reading.isLive()).isFalse();

        assertThat(reading.temperatureC()).isBetween(-10.0, 30.0);
        assertThat(reading.rainfallMm()).isBetween(0.0, 10.0);
        assertThat(reading.humidity())
                .as("humidity is expressed 0-1, not as a percentage")
                .isBetween(0.0, 1.0);
        assertThat(reading.windSpeedKmh()).isBetween(0.0, 60.0);
    }

    @Test
    @DisplayName("the four seasons differ, and in the direction a Dane would expect")
    void seasonsAreDistinctAndOrderedSensibly() {
        WeatherService service = new WeatherService(RestClient.builder(), properties());

        WeatherReadingDto winter = service.seasonalEstimate(LocalDateTime.of(2026, Month.JANUARY, 15, 8, 0));
        WeatherReadingDto spring = service.seasonalEstimate(LocalDateTime.of(2026, Month.APRIL, 15, 8, 0));
        WeatherReadingDto summer = service.seasonalEstimate(LocalDateTime.of(2026, Month.JULY, 15, 8, 0));
        WeatherReadingDto autumn = service.seasonalEstimate(LocalDateTime.of(2026, Month.OCTOBER, 15, 8, 0));

        assertThat(winter.temperatureC()).isLessThan(spring.temperatureC());
        assertThat(spring.temperatureC()).isLessThan(summer.temperatureC());
        assertThat(autumn.temperatureC()).isLessThan(summer.temperatureC());

        // Winter is the wettest, windiest and most humid of the four.
        assertThat(winter.windSpeedKmh()).isGreaterThan(summer.windSpeedKmh());
        assertThat(winter.humidity()).isGreaterThan(summer.humidity());

        assertThat(winter.temperatureC()).isEqualTo(2.0, within(1e-9));
        assertThat(spring.temperatureC()).isEqualTo(8.5, within(1e-9));
        assertThat(summer.temperatureC()).isEqualTo(17.5, within(1e-9));
        assertThat(autumn.temperatureC()).isEqualTo(9.5, within(1e-9));
    }

    @Test
    @DisplayName("December, January and February all count as winter")
    void winterCoversAllThreeWinterMonths() {
        WeatherService service = new WeatherService(RestClient.builder(), properties());

        for (Month month : new Month[]{Month.DECEMBER, Month.JANUARY, Month.FEBRUARY}) {
            WeatherReadingDto reading = service.seasonalEstimate(LocalDateTime.of(2026, month, 10, 6, 0));
            assertThat(reading.temperatureC()).as("%s", month).isEqualTo(2.0, within(1e-9));
            assertThat(reading.source()).isEqualTo(WeatherReadingDto.SOURCE_FALLBACK);
        }
    }

    // ------------------------------------------------------------------
    // forecastFor - which path it takes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a good Open-Meteo response is used and marked as live")
    void liveForecastIsParsedAndMarkedLive() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WeatherService service = new WeatherService(builder, properties());

        server.expect(requestTo(containsString("latitude=55.5")))
                .andRespond(withSuccess("""
                        {
                          "hourly": {
                            "time": ["2026-01-15T07:00", "2026-01-15T08:00"],
                            "temperature_2m": [1.0, 3.5],
                            "precipitation": [0.0, 1.4],
                            "relative_humidity_2m": [80, 92],
                            "wind_speed_10m": [10.0, 24.0]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        WeatherReadingDto reading =
                service.forecastFor(55.5, 11.5, LocalDateTime.of(2026, 1, 15, 8, 0));

        server.verify();
        assertThat(reading.source()).isEqualTo(WeatherReadingDto.SOURCE_LIVE);
        assertThat(reading.isLive()).isTrue();
        assertThat(reading.temperatureC()).isEqualTo(3.5, within(1e-9));
        assertThat(reading.rainfallMm()).isEqualTo(1.4, within(1e-9));
        assertThat(reading.windSpeedKmh()).isEqualTo(24.0, within(1e-9));
        assertThat(reading.humidity())
                .as("Open-Meteo reports a percentage; the model works in 0-1")
                .isEqualTo(0.92, within(1e-9));
    }

    @Test
    @DisplayName("when Open-Meteo fails the request still succeeds, on a seasonal estimate")
    void upstreamFailureFallsBackInsteadOfThrowing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WeatherService service = new WeatherService(builder, properties());

        server.expect(requestTo(containsString("latitude"))).andRespond(withServerError());

        WeatherReadingDto reading =
                service.forecastFor(55.5, 11.5, LocalDateTime.of(2026, 7, 4, 14, 0));

        server.verify();
        assertThat(reading.source()).isEqualTo(WeatherReadingDto.SOURCE_FALLBACK);
        assertThat(reading.temperatureC()).isEqualTo(17.5, within(1e-9));
    }

    @Test
    @DisplayName("a response with no entry for the departure hour also falls back")
    void missingHourFallsBack() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WeatherService service = new WeatherService(builder, properties());

        server.expect(requestTo(containsString("latitude")))
                .andRespond(withSuccess("""
                        {
                          "hourly": {
                            "time": ["2026-01-15T07:00"],
                            "temperature_2m": [1.0],
                            "precipitation": [0.0],
                            "relative_humidity_2m": [80],
                            "wind_speed_10m": [10.0]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        WeatherReadingDto reading =
                service.forecastFor(55.5, 11.5, LocalDateTime.of(2026, 1, 15, 23, 0));

        server.verify();
        assertThat(reading.source()).isEqualTo(WeatherReadingDto.SOURCE_FALLBACK);
        assertThat(reading.temperatureC()).isEqualTo(2.0, within(1e-9));
    }
}
