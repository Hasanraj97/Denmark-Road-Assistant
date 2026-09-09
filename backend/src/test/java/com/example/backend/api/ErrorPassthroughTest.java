package com.example.backend.api;

import com.example.backend.dto.RouteObservationDto;
import com.example.backend.support.AbstractApiTest;
import com.example.backend.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Defect <strong>F1</strong>: downstream failures must keep their own status and
 * their own words.
 *
 * <p>{@code ApiExceptionHandler} used to have a catch-all
 * {@code @ExceptionHandler(Exception.class)} and nothing more specific. A
 * {@link ResponseStatusException} is an {@code Exception}, so the catch-all won
 * every time, and every carefully written message the code raised - "start the
 * Python ml-service on port 8000", "could not match that place in Denmark" - was
 * flattened into HTTP 500 "Something went wrong on the server." The user was
 * told the server was broken when in fact they had made a typo, or a service
 * they could start themselves was down.</p>
 *
 * <p>These tests assert the status <em>and</em> the exact text, because a 502
 * carrying the generic message would still be a failure of the same kind.</p>
 */
class ErrorPassthroughTest extends AbstractApiTest {

    private static final String ML_UNAVAILABLE =
            "The road-condition model service is unavailable. Start the Python ml-service on port 8000.";

    private static final String NO_SUCH_PLACE =
            "Could not match \"Atlantis\" to a place in Denmark. "
                    + "Try a more specific Denmark town, address or postcode.";

    private static final String GENERIC_500 = "Something went wrong on the server.";

    private void stubHappyRoute() {
        when(geocodingService.geocode("Copenhagen")).thenReturn(TestFixtures.copenhagen());
        when(geocodingService.geocode("Roskilde")).thenReturn(TestFixtures.roskilde());
        when(routingService.computeRoute(any(), any())).thenReturn(TestFixtures.route());
        when(routingService.toDto(any())).thenReturn(TestFixtures.routeResponse());
    }

    @Test
    @DisplayName("ML service down -> 502 with the ML service's own message, not a generic 500")
    void mlServiceFailureSurfacesAs502WithItsOwnMessage() throws Exception {
        stubHappyRoute();
        when(mlServiceClient.predict(any(RouteObservationDto.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, ML_UNAVAILABLE));

        mockMvc.perform(jsonPost("/api/route-and-predict", TestFixtures.routeRequest()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(ML_UNAVAILABLE))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(GENERIC_500)))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        // Nothing may be written to history when the analysis failed.
        org.assertj.core.api.Assertions.assertThat(historyRepository.count()).isZero();
    }

    @Test
    @DisplayName("an unknown place -> 404 with the geocoder's explanation")
    void geocodingNotFoundSurfacesAs404() throws Exception {
        when(geocodingService.geocode(anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, NO_SUCH_PLACE));

        mockMvc.perform(jsonPost("/api/route-and-predict", TestFixtures.routeRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(NO_SUCH_PLACE));

        // The request must stop at the geocoder - no routing, no prediction.
        verify(routingService, never()).computeRoute(any(), any());
        verify(mlServiceClient, never()).predict(any(RouteObservationDto.class));
    }

    @Test
    @DisplayName("a routing failure -> 502 with the routing service's message")
    void routingFailureSurfacesAs502() throws Exception {
        when(geocodingService.geocode(anyString())).thenReturn(TestFixtures.copenhagen());
        when(routingService.computeRoute(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Could not build a Denmark road route for those locations."));

        mockMvc.perform(jsonPost("/api/route-and-predict", TestFixtures.routeRequest()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message")
                        .value("Could not build a Denmark road route for those locations."));
    }

    @Test
    @DisplayName("the same passthrough applies to POST /api/route")
    void routeEndpointAlsoPassesTheStatusThrough() throws Exception {
        when(geocodingService.geocode(anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, NO_SUCH_PLACE));

        mockMvc.perform(jsonPost("/api/route", TestFixtures.routeRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(NO_SUCH_PLACE));
    }

    @Test
    @DisplayName("a genuinely unexpected failure is still a 500, but with a traceable reference")
    void unexpectedFailureIsAGenericFiveHundredWithAReference() throws Exception {
        stubHappyRoute();
        when(mlServiceClient.predict(any(RouteObservationDto.class)))
                .thenThrow(new IllegalStateException("something nobody anticipated"));

        mockMvc.perform(jsonPost("/api/route-and-predict", TestFixtures.routeRequest()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.startsWith("Something went wrong on the server. Reference: ")))
                // The internal exception text must never reach the browser.
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("nobody anticipated"))));
    }

    @Test
    @DisplayName("a missing origin is a 400 naming the field, not a 500")
    void validationFailureIsABadRequest() throws Exception {
        mockMvc.perform(jsonPost("/api/route-and-predict",
                        new com.example.backend.dto.RouteRequestDto("  ", "Roskilde", "08:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Origin is required"));
    }
}
