package com.example.backend.support;

import com.example.backend.dto.FeatureInfluenceDto;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.ModelInfoDto;
import com.example.backend.dto.PredictionResponseDto;
import com.example.backend.dto.RouteFeaturesDto;
import com.example.backend.dto.RoutePointDto;
import com.example.backend.dto.RouteRequestDto;
import com.example.backend.dto.RouteResponseDto;
import com.example.backend.dto.SignupRequest;
import com.example.backend.dto.UpdateProfileRequest;
import com.example.backend.dto.WeatherReadingDto;
import com.example.backend.routing.GeocodedLocation;
import com.example.backend.routing.RouteComputation;

import java.util.List;
import java.util.Map;

/**
 * Canned objects the tests share. Values are realistic but arbitrary; nothing
 * here reaches a network or a real service.
 */
public final class TestFixtures {

    public static final String PASSWORD = "correct-horse-battery";

    public static final String ORIGIN_LABEL = "København, Region Hovedstaden, Danmark";
    public static final String DESTINATION_LABEL = "Roskilde, Roskilde Kommune, Region Sjælland, Danmark";

    private TestFixtures() {
    }

    public static SignupRequest signup(String email) {
        return new SignupRequest(
                "Test Driver",
                email,
                "+45 12 34 56 78",
                "Nørrebrogade 1, 2200 København",
                PASSWORD
        );
    }

    public static LoginRequest login(String email, String password) {
        return new LoginRequest(email, password);
    }

    public static UpdateProfileRequest profileUpdate(String fullName, String email) {
        return new UpdateProfileRequest(
                fullName,
                email,
                "+45 87 65 43 21",
                "Vestergade 12, 4000 Roskilde"
        );
    }

    public static RouteRequestDto routeRequest() {
        return new RouteRequestDto("Copenhagen", "Roskilde", null, "08:00");
    }

    public static GeocodedLocation copenhagen() {
        return new GeocodedLocation(ORIGIN_LABEL, 55.6761, 12.5683, 0.92);
    }

    public static GeocodedLocation roskilde() {
        return new GeocodedLocation(DESTINATION_LABEL, 55.6415, 12.0803, 0.60);
    }

    public static RouteComputation route() {
        return new RouteComputation(
                copenhagen(),
                roskilde(),
                32.4,
                28.0,
                List.of(new RoutePointDto(55.6761, 12.5683), new RoutePointDto(55.6415, 12.0803))
        );
    }

    public static RouteResponseDto routeResponse() {
        RouteComputation route = route();
        return new RouteResponseDto(
                route.origin().displayName(),
                route.destination().displayName(),
                route.distanceKm(),
                route.durationMinutes(),
                route.geometry()
        );
    }

    public static WeatherReadingDto weather() {
        return new WeatherReadingDto(6.5, 0.4, 0.81, 19.0, WeatherReadingDto.SOURCE_LIVE);
    }

    public static RouteFeaturesDto features() {
        return new RouteFeaturesDto(
                32.4,
                28.0,
                69.4,
                0.72,
                0.61,
                0.38,
                0.4,
                "morning",
                "Monday",
                0.76,
                0.29,
                0.18,
                0.21,
                6.5,
                0.81,
                19.0,
                "autumn",
                "urban",
                0.55
        );
    }

    public static PredictionResponseDto prediction() {
        return new PredictionResponseDto(
                "Moderate",
                0.83,
                7,
                "Allow a little extra time; the surface may be wet near Roskilde.",
                List.of(new FeatureInfluenceDto("traffic_level", "0.61", "increases risk")),
                Map.of("Good", 0.11, "Moderate", 0.83, "Poor", 0.06),
                features(),
                true,
                "Trained on generated data."
        );
    }

    public static ModelInfoDto modelInfo() {
        return new ModelInfoDto(
                "RandomForestClassifier",
                12000,
                0.87,
                List.of("Good", "Moderate", "Poor"),
                Map.of("traffic_level", 0.21, "weather_risk", 0.18),
                "Trained on generated data."
        );
    }
}
