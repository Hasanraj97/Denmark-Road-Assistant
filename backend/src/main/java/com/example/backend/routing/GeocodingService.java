package com.example.backend.routing;

import com.example.backend.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Service
public class GeocodingService {

    private final RestClient restClient;
    private final AppProperties appProperties;

    public GeocodingService(RestClient.Builder builder, AppProperties appProperties) {
        this.restClient = builder
                .defaultHeader(HttpHeaders.USER_AGENT, "denmark-road-condition-assistant/1.0")
                .build();
        this.appProperties = appProperties;
    }

    public GeocodedLocation geocode(String query) {
        URI searchUri = UriComponentsBuilder
                .fromUriString("https://nominatim.openstreetmap.org/search")
                .queryParam("q", query)
                .queryParam("format", "jsonv2")
                .queryParam("limit", 1)
                .queryParam("countrycodes", appProperties.countryCode())
                .encode()
                .build()
                .toUri();

        JsonNode[] results = restClient.get()
                .uri(searchUri)
                .retrieve()
                .body(JsonNode[].class);

        if (results == null || results.length == 0) {
            throw new ResponseStatusException(
                    BAD_GATEWAY,
                    "Could not match that place in Denmark. Try a more specific Denmark location or address."
            );
        }

        JsonNode first = results[0];
        return new GeocodedLocation(
                first.path("display_name").asText(query),
                first.path("lat").asDouble(),
                first.path("lon").asDouble()
        );
    }


}
