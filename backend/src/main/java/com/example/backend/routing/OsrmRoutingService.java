package com.example.backend.routing;

import com.example.backend.dto.RoutePointDto;
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
                "https://router.project-osrm.org/route/v1/driving/" + coordinates +
                        "?overview=full&geometries=geojson"
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
                geometry
        );
    }

    public RouteResponseDto toDto(RouteComputation route) {
        return new RouteResponseDto(
                route.origin().displayName(),
                route.destination().displayName(),
                route.distanceKm(),
                route.durationMinutes(),
                route.geometry()
        );
    }
}
