package com.example.backend.traffic;

import com.example.backend.dto.RoutePointDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The geometry and text handling behind "what is reported on my route".
 *
 * <p>These are the pure parts of {@link TrafficEventService} - no network, no
 * Spring context. The feed itself is deliberately not contacted here; a test that
 * depended on live Danish roadworks would pass or fail depending on the traffic.</p>
 */
class TrafficEventServiceTest {

    /** Roughly the E45 heading north past Horsens. */
    private static final List<RoutePointDto> ROUTE = List.of(
            new RoutePointDto(55.8500, 9.8000),
            new RoutePointDto(55.8600, 9.8100),
            new RoutePointDto(55.8700, 9.8200),
            new RoutePointDto(55.8800, 9.8300)
    );

    // ------------------------------------------------------------------
    // distance
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a known distance is measured correctly")
    void haversineMatchesAKnownDistance() {
        // Copenhagen city hall to Roskilde cathedral, about 30 km apart.
        double metres = TrafficEventService.haversineMetres(55.6759, 12.5655, 55.6427, 12.0803);

        assertThat(metres).isCloseTo(30_400, within(1_500.0));
    }

    @Test
    @DisplayName("the same point is zero metres away")
    void samePointIsZero() {
        assertThat(TrafficEventService.haversineMetres(55.86, 9.81, 55.86, 9.81))
                .isCloseTo(0.0, within(0.001));
    }

    @Test
    @DisplayName("an event sitting on the route measures as almost zero")
    void pointOnTheRouteIsOnTheRoute() {
        double distance = TrafficEventService.distanceToRouteMetres(ROUTE, 55.8600, 9.8100);

        assertThat(distance).isLessThan(1.0);
    }

    @Test
    @DisplayName("distance is to the NEAREST part of the route, not the first point")
    void distanceUsesTheNearestVertex() {
        // Sits beside the LAST vertex. A naive implementation that measured from the
        // start of the route would report roughly 3.5 km instead.
        double distance = TrafficEventService.distanceToRouteMetres(ROUTE, 55.8801, 9.8301);

        assertThat(distance).isLessThan(20.0);
    }

    @Test
    @DisplayName("an event well off the route is far away")
    void pointFarFromTheRouteIsFar() {
        // Aalborg, nowhere near this stretch.
        double distance = TrafficEventService.distanceToRouteMetres(ROUTE, 57.0488, 9.9217);

        assertThat(distance).isGreaterThan(100_000.0);
    }

    // ------------------------------------------------------------------
    // the bounding box pre-filter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the bounding box keeps nearby events and discards distant ones")
    void boundingBoxFiltersSensibly() {
        TrafficEventService.BoundingBox box = TrafficEventService.BoundingBox.around(ROUTE, 150.0);

        assertThat(box.contains(55.8600, 9.8100)).as("on the route").isTrue();
        assertThat(box.contains(55.8801, 9.8301)).as("just past the end, inside the padding").isTrue();
        assertThat(box.contains(57.0488, 9.9217)).as("Aalborg").isFalse();
        assertThat(box.contains(55.6759, 12.5655)).as("Copenhagen").isFalse();
    }

    // ------------------------------------------------------------------
    // text tidying
    // ------------------------------------------------------------------

    @Test
    @DisplayName("junction markers are named rather than left looking like broken markup")
    void junctionMarkersBecomeReadable() {
        String raw = "Advarsel - E45 fra Vejle mod Aarhus mellem &lt;56a&gt; Horsens V og &lt;55&gt; Horsens N";

        String cleaned = TrafficEventService.cleanText(raw);

        assertThat(cleaned).isEqualTo(
                "Advarsel - E45 fra Vejle mod Aarhus mellem exit 56a Horsens V og exit 55 Horsens N");
        assertThat(cleaned).doesNotContain("&lt;").doesNotContain("<56a>");
    }

    @Test
    @DisplayName("embedded HTML is stripped and whitespace collapsed")
    void htmlIsStripped() {
        String raw = "<p>Rute 6 fra Hillerod mod Helsingor</p><p>Uheld</p>";

        assertThat(TrafficEventService.cleanText(raw)).isEqualTo("Rute 6 fra Hillerod mod Helsingor Uheld");
    }

    @ParameterizedTest
    @CsvSource({"&amp;, &", "&nbsp;, ''"})
    @DisplayName("common entities are decoded")
    void entitiesAreDecoded(String raw, String expected) {
        assertThat(TrafficEventService.cleanText(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("null and blank text do not blow up")
    void nullIsSafe() {
        assertThat(TrafficEventService.cleanText(null)).isEmpty();
        assertThat(TrafficEventService.cleanText("   ")).isEmpty();
    }
}
