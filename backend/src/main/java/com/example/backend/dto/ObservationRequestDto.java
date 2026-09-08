package com.example.backend.dto;

/**
 * Envelope for {@code POST /predict-road-condition} when sending raw facts.
 *
 * <p>The ML service accepts either this shape or a fully derived
 * {@code features} object. This one is the preferred path, because the features
 * are then derived by the same code that produced the training data.</p>
 */
public record ObservationRequestDto(
        RouteObservationDto observation
) {
}
