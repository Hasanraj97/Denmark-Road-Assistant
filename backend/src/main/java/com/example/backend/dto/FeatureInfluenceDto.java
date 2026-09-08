package com.example.backend.dto;

public record FeatureInfluenceDto(
        String feature,
        String value,
        String impact
) {
}
