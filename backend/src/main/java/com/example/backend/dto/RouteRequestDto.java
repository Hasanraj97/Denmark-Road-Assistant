package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What the user asked for.
 *
 * @param selectedDepartureDate the day to travel, as {@code yyyy-MM-dd}. Optional -
 *        left out, it means today. It exists because the app previously accepted a
 *        time only and guessed the date (today, or tomorrow if the time had already
 *        passed). That capped every question at 24 hours ahead, which mattered more
 *        than it sounds: {@code day_of_week} and {@code season} are two of the
 *        model's nineteen features, so neither could ever be varied. You could not
 *        ask what a route looks like next Saturday, or in January.
 * @param selectedDepartureTime the time to leave, as {@code HH:mm}.
 */
public record RouteRequestDto(
        @NotBlank(message = "Origin is required")
        @Size(max = 200, message = "Origin is too long")
        String origin,

        @NotBlank(message = "Destination is required")
        @Size(max = 200, message = "Destination is too long")
        String destination,

        @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$",
                message = "Departure date must look like 2026-09-15")
        String selectedDepartureDate,

        @Pattern(regexp = "^$|^\\d{2}:\\d{2}(:\\d{2})?$",
                message = "Departure time must look like 17:00")
        String selectedDepartureTime
) {
}
