package com.example.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendOrigins,
        String mlServiceBaseUrl,
        double denmarkCenterLat,
        double denmarkCenterLng,
        String countryCode,
        String jwtSecret,
        long jwtExpirationMs
) {
}
