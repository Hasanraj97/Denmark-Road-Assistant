package com.example.backend.routing;

import com.example.backend.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

import static org.springframework.http.HttpStatus.NOT_FOUND;

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
            // 404, not 502. Nothing is wrong with the geocoding service - the place
            // simply does not match anything in Denmark. Reporting an ordinary typo
            // as a "Bad Gateway" made the user think the server was broken.
            throw new ResponseStatusException(
                    NOT_FOUND,
                    "Could not match \"" + query + "\" to a place in Denmark. "
                            + "Try a more specific Denmark town, address or postcode."
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
