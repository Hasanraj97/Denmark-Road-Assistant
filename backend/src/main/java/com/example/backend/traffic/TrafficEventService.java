package com.example.backend.traffic;

import com.example.backend.config.AppProperties;
import com.example.backend.dto.RouteIncidentDto;
import com.example.backend.dto.RoutePointDto;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finds real, currently reported traffic events that lie on the user's route.
 *
 * <h2>Where the data comes from</h2>
 *
 * <p>Vejdirektoratet, the Danish Road Directorate, publishes live traffic events -
 * roadworks, accidents, queues, roadblocks, wind and ice warnings - as the feed
 * behind their own public traffic map at trafikkort.vejdirektoratet.dk.</p>
 *
 * <p>Their documented SDK points at {@code data.vd-nap.dk}, which no longer
 * resolves, and their registered API needs an account and a key. The feed used
 * here is the one their live map itself reads: a public Google Cloud Storage
 * object needing no key and no registration. Records carry WGS84 coordinates, so
 * they line up directly with the OSRM route geometry.</p>
 *
 * <p><strong>Caveat, stated plainly because it belongs in the write-up:</strong>
 * this endpoint is undocumented. It is what the public map uses, not a published
 * contract, so it could change without notice. A failure here is treated as "no
 * information", never as an error - the route analysis still completes.</p>
 *
 * <h2>Why this is shown but not modelled</h2>
 *
 * <p>These events are the only genuinely measured road information in the whole
 * application. It is tempting to feed the count straight into
 * {@code construction_risk_score}. That would be a mistake: the model was trained
 * on a generated distribution where that feature is an assumption about
 * likelihood, not a count of real events, so substituting one for the other would
 * recreate the train/serve mismatch this project spent its largest phase
 * removing. The events are displayed alongside the grade, not folded into it.</p>
 */
@Service
public class TrafficEventService {

    private static final Logger log = LoggerFactory.getLogger(TrafficEventService.class);

    /** How close to the driven line an event must be to count as "on your route". */
    private static final double MATCH_RADIUS_METRES = 150.0;

    /** Never overwhelm the user; the closest few are what matter. */
    private static final int MAX_INCIDENTS = 8;

    /** The feed is small and changes slowly. One fetch per minute is plenty. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(1);

    private static final DateTimeFormatter VD_TIMESTAMP =
            DateTimeFormatter.ofPattern("dd-MM-yyyy 'kl.' HH:mm", Locale.ROOT);

    /**
     * Vejdirektoratet's DATEX II type names, mapped to something a driver can
     * read. The raw values look like
     * {@code org.vd.trafficmap.datexii.v32.datamodel.RoadOrCarriagewayOrLaneManagement}.
     */
    private static final Map<String, String> CATEGORY_NAMES = Map.of(
            "RoadOrCarriagewayOrLaneManagement", "Roadworks or lane closure",
            "ConstructionWorks", "Roadworks",
            "Accident", "Accident",
            "AbnormalTraffic", "Queue",
            "GeneralObstruction", "Obstruction",
            "VehicleObstruction", "Broken-down vehicle",
            "AnimalPresenceObstruction", "Animals on the road",
            "PoorEnvironmentConditions", "Poor conditions",
            "WeatherRelatedRoadConditions", "Weather on the road",
            "SpeedManagement", "Speed restriction"
    );

    private final RestClient restClient;
    private final AppProperties appProperties;

    /** Cached raw feed, so a burst of analyses does not hammer a public endpoint. */
    private final AtomicReference<CachedFeed> cache = new AtomicReference<>(null);

    public TrafficEventService(RestClient.Builder builder, AppProperties appProperties) {
        this.restClient = builder.build();
        this.appProperties = appProperties;
    }

    private record CachedFeed(JsonNode body, Instant fetchedAt) {
    }

    /**
     * Returns the reported events lying within {@link #MATCH_RADIUS_METRES} of the
     * route, nearest first. Never throws: if the feed cannot be read, the result is
     * an empty list and the analysis carries on without it.
     */
    public List<RouteIncidentDto> incidentsOnRoute(List<RoutePointDto> geometry, LocalDateTime departure) {
        if (geometry == null || geometry.isEmpty()) {
            return List.of();
        }

        JsonNode feed = loadFeed();
        if (feed == null || !feed.isArray()) {
            return List.of();
        }

        // A cheap bounding box around the route, padded by the match radius, so the
        // expensive distance check only runs on events that could possibly match.
        BoundingBox box = BoundingBox.around(geometry, MATCH_RADIUS_METRES);

        List<RouteIncidentDto> found = new ArrayList<>();
        for (JsonNode layer : feed) {
            for (JsonNode feature : layer.path("features")) {
                JsonNode coordinates = feature.path("geometry").path("coordinates");
                if (!coordinates.isArray() || coordinates.size() < 2) {
                    continue;
                }
                double longitude = coordinates.path(0).asDouble();
                double latitude = coordinates.path(1).asDouble();
                if (!box.contains(latitude, longitude)) {
                    continue;
                }

                double distance = distanceToRouteMetres(geometry, latitude, longitude);
                if (distance > MATCH_RADIUS_METRES) {
                    continue;
                }

                found.add(toIncident(feature.path("properties"), latitude, longitude, distance, departure));
            }
        }

        return tidy(found);
    }

    /**
     * Turns the raw matches into something worth showing.
     *
     * <p>Two properties of the feed make this necessary, both confirmed by
     * measuring it: it <strong>retains recently cleared events</strong> - on a
     * typical read, 199 of 207 records had already ended - and it publishes
     * <strong>progressive updates as separate records</strong>, so a single real
     * queue appeared ten times with ten different end times.</p>
     *
     * <p>So: drop anything already finished by the time the user sets off, keep one
     * record per distinct headline (the longest-lasting, which is the latest
     * revision), and show the closest few.</p>
     */
    private List<RouteIncidentDto> tidy(List<RouteIncidentDto> found) {
        Map<String, RouteIncidentDto> bestByHeadline = new LinkedHashMap<>();

        for (RouteIncidentDto incident : found) {
            if (!incident.activeAtDeparture()) {
                continue;
            }
            String key = incident.headline().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            RouteIncidentDto existing = bestByHeadline.get(key);
            if (existing == null || outlasts(incident, existing)) {
                bestByHeadline.put(key, incident);
            }
        }

        List<RouteIncidentDto> tidied = new ArrayList<>(bestByHeadline.values());
        tidied.sort(Comparator.comparingInt(RouteIncidentDto::distanceFromRouteMetres));
        return tidied.size() > MAX_INCIDENTS
                ? List.copyOf(tidied.subList(0, MAX_INCIDENTS))
                : List.copyOf(tidied);
    }

    /** An open-ended event outlasts any dated one; otherwise the later end wins. */
    private boolean outlasts(RouteIncidentDto candidate, RouteIncidentDto existing) {
        LocalDateTime candidateEnd = parseVdTimestamp(candidate.endsAt());
        LocalDateTime existingEnd = parseVdTimestamp(existing.endsAt());
        if (candidateEnd == null) {
            return true;
        }
        if (existingEnd == null) {
            return false;
        }
        return candidateEnd.isAfter(existingEnd);
    }

    private RouteIncidentDto toIncident(JsonNode properties, double latitude, double longitude,
                                        double distance, LocalDateTime departure) {
        String rawType = properties.path("TrafficMan2_Type").asText("");
        String shortType = rawType.contains(".") ? rawType.substring(rawType.lastIndexOf('.') + 1) : rawType;
        String category = CATEGORY_NAMES.getOrDefault(shortType, "Traffic report");

        String startsAt = emptyToNull(properties.path("beginPeriod").asText(""));
        String endsAt = emptyToNull(properties.path("endPeriod").asText(""));

        return new RouteIncidentDto(
                category,
                cleanText(properties.path("title").asText("")),
                cleanText(properties.path("header").asText("")),
                latitude,
                longitude,
                (int) Math.round(distance),
                startsAt,
                endsAt,
                isActiveAt(startsAt, endsAt, departure)
        );
    }

    /**
     * Is the event expected to still be going when the user sets off?
     *
     * <p>An event with no end time is open-ended and counts as active. An
     * unparseable timestamp is treated as active too - better to mention something
     * that has cleared than to hide something that has not.</p>
     */
    private boolean isActiveAt(String startsAt, String endsAt, LocalDateTime departure) {
        LocalDateTime end = parseVdTimestamp(endsAt);
        if (end != null && end.isBefore(departure)) {
            return false;
        }
        LocalDateTime start = parseVdTimestamp(startsAt);
        return start == null || !start.isAfter(departure);
    }

    private LocalDateTime parseVdTimestamp(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), VD_TIMESTAMP);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /** Strips the HTML and escaped entities Vejdirektoratet embeds in its text. */
    static String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]*>", " ")
                // Vejdirektoratet marks motorway junctions as &lt;56b&gt;. Unescaping
                // blindly would leave "<56b>" on screen, which reads like broken
                // markup, so name it for what it is first.
                .replaceAll("&lt;\\s*([0-9]+[A-Za-z]?)\\s*&gt;", "exit $1")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? null : value;
    }

    private JsonNode loadFeed() {
        CachedFeed cached = cache.get();
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached.body();
        }

        try {
            JsonNode body = restClient.get()
                    .uri(URI.create(appProperties.trafficEventsUrl()))
                    .retrieve()
                    .body(JsonNode.class);
            if (body != null) {
                cache.set(new CachedFeed(body, Instant.now()));
            }
            return body;
        } catch (Exception exception) {
            // Not fatal, by design. This is extra information layered on top of the
            // analysis; losing it must not lose the analysis.
            log.warn("Could not read the Vejdirektoratet traffic feed ({}). Continuing without incidents.",
                    exception.toString());
            return cached != null ? cached.body() : null;
        }
    }

    /**
     * Shortest distance from a point to the route, approximated as the distance to
     * the nearest route vertex.
     *
     * <p>OSRM returns a dense polyline - roughly 800 points for a 36 km route, so
     * tens of metres apart - which makes nearest-vertex accurate enough at a 150 m
     * threshold and far simpler than true point-to-segment projection.</p>
     */
    static double distanceToRouteMetres(List<RoutePointDto> geometry, double latitude, double longitude) {
        double best = Double.MAX_VALUE;
        for (RoutePointDto point : geometry) {
            double distance = haversineMetres(latitude, longitude, point.lat(), point.lng());
            if (distance < best) {
                best = distance;
            }
        }
        return best;
    }

    static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadius = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Cheap rectangular pre-filter so the distance loop runs on few candidates. */
    record BoundingBox(double minLat, double maxLat, double minLon, double maxLon) {

        static BoundingBox around(List<RoutePointDto> geometry, double paddingMetres) {
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            for (RoutePointDto point : geometry) {
                minLat = Math.min(minLat, point.lat());
                maxLat = Math.max(maxLat, point.lat());
                minLon = Math.min(minLon, point.lng());
                maxLon = Math.max(maxLon, point.lng());
            }
            double latPadding = paddingMetres / 111_320.0;
            double lonPadding = paddingMetres / (111_320.0 * Math.max(0.1, Math.cos(Math.toRadians(minLat))));
            return new BoundingBox(minLat - latPadding, maxLat + latPadding,
                    minLon - lonPadding, maxLon + lonPadding);
        }

        boolean contains(double latitude, double longitude) {
            return latitude >= minLat && latitude <= maxLat && longitude >= minLon && longitude <= maxLon;
        }
    }
}
