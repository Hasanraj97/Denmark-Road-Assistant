package com.example.backend.routing;

import com.example.backend.dto.RouteFeaturesDto;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

@Service
public class FeatureEngineeringService {

    public RouteFeaturesDto deriveFeatures(RouteComputation route, String selectedDepartureTime) {
        ZonedDateTime now = ZonedDateTime.now(java.time.ZoneId.of("Europe/Copenhagen"));
        LocalTime departureTime = normalizeDepartureTime(selectedDepartureTime, now.toLocalTime());
        int departureHour = departureTime.getHour();

        double distanceKm = route.distanceKm();
        double durationMin = route.durationMinutes();
        double avgSpeed = durationMin > 0 ? distanceKm / (durationMin / 60.0) : 0.0;

        String timeOfDay = timeOfDay(departureHour);
        String season = season(now.getMonth());
        String dayOfWeek = now.getDayOfWeek().name().toLowerCase(Locale.ROOT);

        double urbanScore = urbanScore(route, distanceKm);
        double timeScore = timeScore(departureHour, now.getDayOfWeek());
        double weatherScore = weatherScore(season);

        String regionType = regionType(urbanScore, distanceKm);

        boolean lowTrafficTime = timeScore <= 0.10;

        double trafficBase = lowTrafficTime ? 0.10 : 0.20;
        double urbanTrafficWeight = lowTrafficTime ? 0.20 : 0.40;
        double timeTrafficWeight = lowTrafficTime ? 0.20 : 0.40;

        double roadTypeScore = clamp(noise(0.20 + avgSpeed / 140.0, route));
        double trafficLevel = clamp(noise(trafficBase + urbanScore * urbanTrafficWeight + timeScore * timeTrafficWeight, route));
        double weatherRisk = clamp(noise(0.20 + weatherScore * 0.60, route));
        double rainfallMm = Math.max(0.0, rounded(noise(weatherRisk * 8.0, route)));
        double humidity = clamp(noise(0.40 + weatherRisk * 0.40, route));
        double windSpeedKmh = Math.max(5.0, rounded(noise(10.0 + weatherRisk * 18.0, route)));
        double temperatureC = rounded(noise(seasonalTemperature(season), route));
        double constructionRisk = clamp(noise(0.10 + urbanScore * 0.25, route));
        double elevationVariation = Math.max(2.0, rounded(noise(5.0 + distanceKm * 0.05, route)));
        double accidentRisk = clamp(noise(0.15 + trafficLevel * 0.35 + weatherRisk * 0.25, route));
        double vehicleLoad = clamp(noise(0.15 + trafficLevel * 0.60, route));

        return new RouteFeaturesDto(
                rounded(distanceKm),
                rounded(durationMin),
                rounded(avgSpeed),
                rounded(roadTypeScore),
                rounded(trafficLevel),
                rounded(weatherRisk),
                rounded(rainfallMm),
                timeOfDay,
                dayOfWeek,
                rounded(urbanScore),
                rounded(accidentRisk),
                rounded(constructionRisk),
                rounded(elevationVariation),
                rounded(temperatureC),
                rounded(humidity),
                rounded(windSpeedKmh),
                season,
                regionType,
                rounded(vehicleLoad)
        );
    }

    private double urbanScore(RouteComputation route, double distanceKm) {
        double score = citySignal(route.origin().displayName(), route.destination().displayName());

        if (distanceKm < 20) {
            score += 0.15;
        }

        if (distanceKm > 100) {
            score -= 0.15;
        }

        return clamp(noise(score, route));
    }

    private double timeScore(int hour, DayOfWeek dayOfWeek) {
        double score;

        if ((hour >= 7 && hour <= 9) || (hour >= 15 && hour <= 18)) {
            score = 0.75;
        } else if (hour >= 10 && hour <= 14) {
            score = 0.50;
        } else if (hour >= 19 && hour <= 21) {
            score = 0.25;
        } else {
            score = 0.05;
        }

        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            score -= 0.10;
        }

        return clamp(score);
    }

    private double weatherScore(String season) {
        return switch (season) {
            case "winter" -> 0.75;
            case "autumn", "spring" -> 0.50;
            default -> 0.25;
        };
    }

    private double citySignal(String originLabel, String destinationLabel) {
        String combined = (originLabel + " " + destinationLabel).toLowerCase(Locale.ROOT);

        if (combined.contains("copenhagen") || combined.contains("kobenhavn") || combined.contains("københavn")) {
            return 0.82;
        }

        if (combined.contains("aarhus") || combined.contains("odense") || combined.contains("aalborg")) {
            return 0.66;
        }

        if (combined.contains("roskilde") || combined.contains("vejle") || combined.contains("esbjerg")) {
            return 0.52;
        }

        return 0.34;
    }

    private String regionType(double urbanScore, double distanceKm) {
        if (urbanScore > 0.68 || distanceKm < 22) {
            return "urban";
        }

        if (urbanScore > 0.44 || distanceKm < 80) {
            return "suburban";
        }

        return "regional";
    }

    private String timeOfDay(int hour) {
        if (hour < 6) {
            return "early_morning";
        }

        if (hour < 10) {
            return "morning_peak";
        }

        if (hour < 15) {
            return "midday";
        }

        if (hour < 19) {
            return "afternoon_peak";
        }

        if (hour < 23) {
            return "evening";
        }

        return "night";
    }

    private LocalTime normalizeDepartureTime(String selectedDepartureTime, LocalTime fallbackTime) {
        if (selectedDepartureTime == null || selectedDepartureTime.isBlank()) {
            return fallbackTime;
        }

        try {
            return LocalTime.parse(selectedDepartureTime);
        } catch (DateTimeParseException exception) {
            return fallbackTime;
        }
    }

    private String season(Month month) {
        return switch (month) {
            case DECEMBER, JANUARY, FEBRUARY -> "winter";
            case MARCH, APRIL, MAY -> "spring";
            case JUNE, JULY, AUGUST -> "summer";
            default -> "autumn";
        };
    }

    private double seasonalTemperature(String season) {
        return switch (season) {
            case "winter" -> 1.5;
            case "spring" -> 9.0;
            case "summer" -> 18.0;
            default -> 10.0;
        };
    }

    private double noise(double value, RouteComputation route) {
        int nameLength = route.origin().displayName().length()
                + route.destination().displayName().length();

        int level = nameLength % 7;
        double percent = (level - 3) / 100.0;

        return value * (1.0 + percent);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double rounded(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}