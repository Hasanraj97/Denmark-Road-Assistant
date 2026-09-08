package com.example.backend.dto;

public record AuthResponse(
        String token,
        UserResponse user
) {
}
