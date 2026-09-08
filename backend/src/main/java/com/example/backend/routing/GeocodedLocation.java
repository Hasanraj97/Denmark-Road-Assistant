package com.example.backend.routing;

/**
 * A place resolved by Nominatim.
 *
 * @param displayName the full human-readable label, e.g. "Roskilde, Roskilde
 *                    Kommune, Region Sjælland, 4000, Danmark"
 * @param latitude    decimal degrees
 * @param longitude   decimal degrees
 * @param urbanScore  how built-up this endpoint is, 0 (open country) to 1 (city
 *                    centre). Scored per endpoint - see {@link GeocodingService}
 *                    for why that matters.
 */
public record GeocodedLocation(
        String displayName,
        double latitude,
        double longitude,
        double urbanScore
) {
}
