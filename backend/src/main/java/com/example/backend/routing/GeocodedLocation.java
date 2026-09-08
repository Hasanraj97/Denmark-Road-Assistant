package com.example.backend.routing;

public record GeocodedLocation(
        String displayName,
        double latitude,
        double longitude
) {
}
