package com.example.backend.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wording a driver actually hears.
 *
 * <p>OSRM deliberately ships no human phrasing - it returns a manoeuvre type and
 * a modifier and leaves the sentence to the client so it can be localised. This
 * covers that translation, because it is read aloud during guidance and a wrong
 * word there is a wrong turn.</p>
 */
class OsrmInstructionTest {

    private final OsrmRoutingService service = new OsrmRoutingService(RestClient.builder());
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode maneuver() {
        return mapper.createObjectNode();
    }

    private ObjectNode roundaboutTaking(int exit) {
        ObjectNode node = mapper.createObjectNode();
        node.put("exit", exit);
        return node;
    }

    @Test
    @DisplayName("a plain turn names the road being joined")
    void turnNamesTheRoad() {
        assertThat(service.describe("turn", "left", "Vestergade", maneuver()))
                .isEqualTo("Turn left onto Vestergade");
    }

    @Test
    @DisplayName("an unnamed road does not produce a dangling 'onto'")
    void unnamedRoadReadsCleanly() {
        assertThat(service.describe("turn", "right", "", maneuver()))
                .isEqualTo("Turn right");
    }

    @Test
    @DisplayName("departure and arrival are phrased as a driver expects")
    void departureAndArrival() {
        assertThat(service.describe("depart", "", "Provstestraede", maneuver()))
                .isEqualTo("Start off on Provstestraede");
        assertThat(service.describe("arrive", "", "", maneuver()))
                .isEqualTo("You have arrived at your destination");
    }

    @Test
    @DisplayName("a roundabout counts the exit")
    void roundaboutCountsTheExit() {
        assertThat(service.describe("roundabout", "right", "Ringvejen", roundaboutTaking(3)))
                .isEqualTo("At the roundabout, take exit 3 onto Ringvejen");
    }

    @Test
    @DisplayName("a roundabout with no exit number still says something usable")
    void roundaboutWithoutExitNumber() {
        assertThat(service.describe("roundabout", "", "", maneuver()))
                .isEqualTo("Go around the roundabout");
    }

    @Test
    @DisplayName("'straight' becomes words rather than being dropped")
    void straightIsSpelledOut() {
        assertThat(service.describe("turn", "straight", "Hovedvejen", maneuver()))
                .isEqualTo("Turn straight ahead onto Hovedvejen");
    }

    @Test
    @DisplayName("an unknown manoeuvre type still yields a sentence, never a blank")
    void unknownTypeFallsBack() {
        assertThat(service.describe("something-new", "left", "Nygade", maneuver()))
                .isNotBlank()
                .contains("Nygade");
    }
}
