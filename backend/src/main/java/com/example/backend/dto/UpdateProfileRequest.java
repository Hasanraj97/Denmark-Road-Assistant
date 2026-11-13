package com.example.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "Full name is required")
        @Size(max = 120, message = "Full name is too long")
        String fullName,
        @NotBlank(message = "Email is required")
        @Email(message = "Email must be valid")
        @Size(max = 180, message = "Email is too long")
        String email,
        // Optional: the app does not need a phone number to analyse trips.
        @Size(max = 40, message = "Phone is too long")
        String phone,
        // Optional: the app does not need an address to analyse trips.
        @Size(max = 255, message = "Address is too long")
        String address
) {
}
