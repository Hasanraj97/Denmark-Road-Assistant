package com.example.backend.routing;

import com.example.backend.dto.RoutePointDto;
import com.example.backend.dto.RouteStepDto;

import java.util.List;

public record RouteComputation(
        GeocodedLocation origin,
        GeocodedLocation destination,
        double distanceKm,
        double durationMinutes,
        List<RoutePointDto> geometry,
        List<RouteStepDto> steps
) {
}
