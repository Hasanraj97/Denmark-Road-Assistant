package com.example.backend.routing;

import com.example.backend.dto.RoutePointDto;
import com.example.backend.dto.RouteStepDto;
import com.example.backend.dto.RouteResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Service
public class OsrmRoutingService {

    /**
     * The public OSRM demo server. OSRM_BASE_URL can point the app at another
     * server (or a local recording proxy used for repeatable tests).
     */
    static final String OSRM_BASE_URL = envOr("OSRM_BASE_URL", "https://router.project-osrm.org/route/v1/driving/");

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final RestClient restClient;

    public OsrmRoutingService(RestClient.Builder builder) {
        this.restClient = builder
                .defaultHeader(HttpHeaders.USER_AGENT, "denmark-road-condition-assistant/1.0")
                .build();
    }

    public RouteComputation computeRoute(GeocodedLocation origin, GeocodedLocation destination) {
        String coordinates = origin.longitude() + "," + origin.latitude() + ";" +
                destination.longitude() + "," + destination.latitude();
        URI routeUri = URI.create(
                OSRM_BASE_URL + coordinates +
                        // steps=true adds the turn-by-turn manoeuvres. Without it OSRM returns
                        // only the shape of the route, which is enough to draw but not
                        // enough to guide anyone along it.
                        "?overview=full&geometries=geojson&steps=true&annotations=false"
        );

        JsonNode routeResponse = restClient.get()
                .uri(routeUri)
                .retrieve()
                .body(JsonNode.class);

        JsonNode routeNode = routeResponse != null ? routeResponse.path("routes").path(0) : null;
        if (routeNode == null || routeNode.isMissingNode()) {
            throw new ResponseStatusException(
                    BAD_GATEWAY,
                    "Could not build a Denmark road route for those locations."
            );
        }

        List<RoutePointDto> geometry = new ArrayList<>();
        for (JsonNode coordinate : routeNode.path("geometry").path("coordinates")) {
            geometry.add(new RoutePointDto(
                    coordinate.path(1).asDouble(),
                    coordinate.path(0).asDouble()
            ));
        }

        if (geometry.isEmpty()) {
            throw new ResponseStatusException(BAD_GATEWAY, "The routing service returned an empty path.");
        }

        return new RouteComputation(
                origin,
                destination,
                routeNode.path("distance").asDouble() / 1000.0,
                routeNode.path("duration").asDouble() / 60.0,
                geometry,
                readSteps(routeNode, geometry)
        );
    }

    /**
     * Flattens OSRM's legs and steps into one ordered instruction list.
     *
     * <p>Each manoeuvre is also matched to the nearest point on the overall route
     * line. That index is what lets the app say how far along the driver already
     * is without re-measuring the whole route on every GPS update.</p>
     */
    private List<RouteStepDto> readSteps(JsonNode routeNode, List<RoutePointDto> geometry) {
        List<RouteStepDto> steps = new ArrayList<>();

        for (JsonNode leg : routeNode.path("legs")) {
            for (JsonNode step : leg.path("steps")) {
                JsonNode maneuver = step.path("maneuver");
                JsonNode location = maneuver.path("location");
                if (!location.isArray() || location.size() < 2) {
                    continue;
                }

                double longitude = location.path(0).asDouble();
                double latitude = location.path(1).asDouble();
                String type = maneuver.path("type").asText("");
                String modifier = maneuver.path("modifier").asText("");
                String roadName = step.path("name").asText("");

                steps.add(new RouteStepDto(
                        describe(type, modifier, roadName, maneuver),
                        type,
                        modifier,
                        roadName,
                        step.path("distance").asDouble(),
                        step.path("duration").asDouble(),
                        latitude,
                        longitude,
                        nearestGeometryIndex(geometry, latitude, longitude)
                ));
            }
        }
        return steps;
    }

    /**
     * Turns OSRM's machine-readable manoeuvre into something a driver can hear.
     *
     * <p>OSRM deliberately does not ship human wording - it leaves phrasing to
     * the client so it can be localised - so this is where "turn / left /
     * Vestergade" becomes "Turn left onto Vestergade".</p>
     */
    String describe(String type, String modifier, String roadName, JsonNode maneuver) {
        String onto = roadName == null || roadName.isBlank() ? "" : " onto " + roadName;
        String along = roadName == null || roadName.isBlank() ? "" : " on " + roadName;

        return switch (type) {
            case "depart" -> "Start off" + along;
            case "arrive" -> "You have arrived at your destination";
            case "turn" -> capitalise(direction(modifier, "Turn")) + onto;
            case "new name" -> "Continue" + along;
            case "merge" -> capitalise(direction(modifier, "Merge")) + onto;
            case "on ramp" -> "Take the slip road" + onto;
            case "off ramp" -> "Take the exit" + onto;
            case "fork" -> capitalise(direction(modifier, "Keep")) + onto;
            case "end of road" -> capitalise(direction(modifier, "Turn")) + " at the end of the road" + onto;
            case "continue" -> "Continue" + (modifier.isBlank() ? "" : " " + modifier) + along;
            case "roundabout", "rotary" -> {
                int exit = maneuver.path("exit").asInt(0);
                yield exit > 0
                        ? "At the roundabout, take exit " + exit + onto
                        : "Go around the roundabout" + onto;
            }
            case "roundabout turn" -> "At the roundabout, " + direction(modifier, "turn") + onto;
            default -> capitalise(direction(modifier, "Continue")) + onto;
        };
    }

    private String direction(String modifier, String verb) {
        if (modifier == null || modifier.isBlank() || "straight".equals(modifier)) {
            return verb + " straight ahead";
        }
        return verb + " " + modifier;
    }

    private String capitalise(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** Index of the route point closest to a manoeuvre. */
    private int nearestGeometryIndex(List<RoutePointDto> geometry, double latitude, double longitude) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < geometry.size(); i++) {
            RoutePointDto point = geometry.get(i);
            double dLat = point.lat() - latitude;
            double dLng = (point.lng() - longitude) * Math.cos(Math.toRadians(latitude));
            double distance = dLat * dLat + dLng * dLng;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    public RouteResponseDto toDto(RouteComputation route) {
        return new RouteResponseDto(
                route.origin().displayName(),
                route.destination().displayName(),
                route.distanceKm(),
                route.durationMinutes(),
                route.geometry(),
                route.steps()
        );
    }
}
