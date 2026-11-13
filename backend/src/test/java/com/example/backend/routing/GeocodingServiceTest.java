package com.example.backend.routing;

import com.example.backend.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Responsible use of the public Nominatim server (review, November 2026):
 * repeated places are served from a cache, and requests are spaced out.
 */
class GeocodingServiceTest {

    private static final String ROSKILDE = """
            [{"display_name":"Roskilde, Roskilde Kommune, Danmark","lat":"55.6415","lon":"12.0803",
              "importance":0.55,"addresstype":"town"}]""";

    private static AppProperties properties() {
        return new AppProperties("http://localhost:4200", "http://localhost:8000", 56.2639, 9.5018, "dk",
                "test-secret-that-is-long-enough-for-hs256!!", 86_400_000L,
                "https://weather.invalid/v1/forecast", 8, "https://traffic.invalid/events.json");
    }

    @Test
    @DisplayName("the same place asked twice is fetched from Nominatim only once")
    void repeatedPlacesComeFromTheCache() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeocodingService service = new GeocodingService(builder, properties());

        server.expect(ExpectedCount.once(), requestTo(containsString("nominatim.openstreetmap.org/search")))
                .andRespond(withSuccess(ROSKILDE, MediaType.APPLICATION_JSON));

        GeocodedLocation first = service.geocode("Roskilde");
        GeocodedLocation second = service.geocode("  roskilde ");

        server.verify();
        assertThat(second).isEqualTo(first);
        assertThat(second.latitude()).isEqualTo(55.6415);
    }

    @Test
    @DisplayName("two different places are requested at least the minimum interval apart")
    void requestsAreSpacedOut() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeocodingService service = new GeocodingService(builder, properties());
        service.minIntervalMillis = 300;

        server.expect(ExpectedCount.twice(), requestTo(containsString("nominatim.openstreetmap.org/search")))
                .andRespond(withSuccess(ROSKILDE, MediaType.APPLICATION_JSON));

        long start = System.nanoTime();
        service.geocode("Roskilde");
        service.geocode("Køge");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        server.verify();
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(280);
    }
}
