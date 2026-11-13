package com.example.backend.routing;

import com.example.backend.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Turns a place the user typed into coordinates, using OpenStreetMap's Nominatim.
 */
@Service
public class GeocodingService {

    /**
     * How built-up the larger Danish towns are. This is the only piece of local
     * knowledge the app hard-codes, and it is small and legible on purpose.
     */
    private static final Map<String, Double> DANISH_URBAN_SCORES = Map.ofEntries(
            Map.entry("københavn", 0.92),
            Map.entry("copenhagen", 0.92),
            Map.entry("frederiksberg", 0.88),
            Map.entry("aarhus", 0.82),
            Map.entry("århus", 0.82),
            Map.entry("odense", 0.78),
            Map.entry("aalborg", 0.76),
            Map.entry("ålborg", 0.76),
            Map.entry("esbjerg", 0.68),
            Map.entry("randers", 0.64),
            Map.entry("kolding", 0.64),
            Map.entry("horsens", 0.62),
            Map.entry("vejle", 0.62),
            Map.entry("roskilde", 0.60),
            Map.entry("herning", 0.56),
            Map.entry("helsingør", 0.56),
            Map.entry("silkeborg", 0.54),
            Map.entry("næstved", 0.52),
            Map.entry("viborg", 0.52),
            Map.entry("køge", 0.50),
            Map.entry("holstebro", 0.48),
            Map.entry("slagelse", 0.48),
            Map.entry("hillerød", 0.48)
    );

    /**
     * The public Nominatim server. NOMINATIM_SEARCH_URL can point the app at
     * another server (or a local recording proxy used for repeatable tests).
     */
    static final String NOMINATIM_SEARCH_URL = envOr("NOMINATIM_SEARCH_URL", "https://nominatim.openstreetmap.org/search");

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Nominatim's usage policy allows at most one request per second. */
    static final long DEFAULT_MIN_INTERVAL_MILLIS = 1000;
    private static final long CACHE_TTL_MILLIS = 24L * 60 * 60 * 1000;
    private static final int CACHE_MAX_ENTRIES = 500;

    private record CachedLocation(GeocodedLocation location, long storedAt) {
    }

    private final RestClient restClient;
    private final AppProperties appProperties;

    /**
     * Recently resolved places. The same towns are looked up again and again
     * (every analysis geocodes two places), so caching them for a day takes most
     * of the load off the public Nominatim server. Oldest entries drop out first.
     */
    private final Map<String, CachedLocation> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedLocation>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedLocation> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            });

    private final Object throttleLock = new Object();
    private long lastRequestAt;
    long minIntervalMillis = DEFAULT_MIN_INTERVAL_MILLIS;

    public GeocodingService(RestClient.Builder builder, AppProperties appProperties) {
        this.restClient = builder
                // Nominatim asks every application to identify itself.
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "denmark-road-condition-assistant/1.0 (student project; github.com/Hasanraj97/denmark-road-assistant)")
                .build();
        this.appProperties = appProperties;
    }

    public GeocodedLocation geocode(String query) {
        String key = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        CachedLocation cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.storedAt() < CACHE_TTL_MILLIS) {
            return cached.location();
        }

        waitForTurn();

        URI searchUri = UriComponentsBuilder
                .fromUriString(NOMINATIM_SEARCH_URL)
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
        String displayName = first.path("display_name").asText(query);

        GeocodedLocation location = new GeocodedLocation(
                displayName,
                first.path("lat").asDouble(),
                first.path("lon").asDouble(),
                urbanScoreFor(displayName, first)
        );
        cache.put(key, new CachedLocation(location, System.currentTimeMillis()));
        return location;
    }

    /**
     * Blocks until at least {@link #minIntervalMillis} has passed since the last
     * request to Nominatim, so the app never sends more than one per second, even
     * when several users analyse routes at the same moment.
     */
    private void waitForTurn() {
        synchronized (throttleLock) {
            long wait = lastRequestAt + minIntervalMillis - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    /**
     * Scores how built-up ONE endpoint is.
     *
     * <p>The previous version glued both place names into a single string and
     * searched that for city names. Two things were wrong with it. Searching the
     * combined text meant an origin of Copenhagen made the <em>destination</em>
     * look like a city too, and vice versa - the score could not tell the two
     * endpoints apart. And because the search ran over the whole Nominatim label,
     * which includes the municipality and region ("Roskilde Kommune, Region
     * Sjælland"), it also matched administrative areas rather than the place
     * itself.</p>
     *
     * <p>Each endpoint is now scored on its own, from the first component of its
     * label - the place name - falling back to Nominatim's own
     * {@code importance} figure for anywhere not in the table. That fallback is
     * what lets a village or a street address get a sensible score instead of one
     * flat default, which also widens the range of values the model sees.</p>
     */
    private double urbanScoreFor(String displayName, JsonNode node) {
        String placeName = displayName.split(",", 2)[0].trim().toLowerCase(Locale.ROOT);

        Double known = DANISH_URBAN_SCORES.get(placeName);
        if (known != null) {
            return known;
        }

        // Nominatim's "importance" ranks how prominent a place is, roughly 0 to 1.
        // A Danish town sits around 0.35-0.55; a minor street is nearer 0.1.
        double importance = node.path("importance").asDouble(0.25);
        double scaled = 0.20 + importance * 0.90;

        // A named settlement is more built-up than a lone address.
        String addressType = node.path("addresstype").asText("");
        if ("city".equals(addressType) || "town".equals(addressType)) {
            scaled += 0.15;
        } else if ("village".equals(addressType) || "hamlet".equals(addressType)) {
            scaled -= 0.05;
        }

        return Math.max(0.05, Math.min(0.95, scaled));
    }
}
