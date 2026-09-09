package com.example.backend.api;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.RouteObservationDto;
import com.example.backend.support.AbstractApiTest;
import com.example.backend.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who gets an analysis written to their history, and who does not.
 *
 * <p>{@code POST /api/route-and-predict} is deliberately public so the app can
 * be demonstrated without an account. That makes the branch worth pinning: a
 * signed-in caller must get a saved row and a {@code historyId}, and an
 * anonymous caller must get the same advisory with {@code historyId: null} and
 * <em>nothing</em> written to the database.</p>
 */
class RouteAnalysisHistoryTest extends AbstractApiTest {

    @BeforeEach
    void stubTheWholePipeline() {
        when(geocodingService.geocode("Copenhagen")).thenReturn(TestFixtures.copenhagen());
        when(geocodingService.geocode("Roskilde")).thenReturn(TestFixtures.roskilde());
        when(routingService.computeRoute(any(), any())).thenReturn(TestFixtures.route());
        when(routingService.toDto(any())).thenReturn(TestFixtures.routeResponse());
        when(mlServiceClient.predict(any(RouteObservationDto.class))).thenReturn(TestFixtures.prediction());
    }

    @Test
    @DisplayName("a signed-in analysis is saved and shows up in GET /api/history")
    void analysisBySignedInUserIsSavedAndListed() throws Exception {
        AuthResponse user = signUp("historian@example.dk");

        mockMvc.perform(jsonPost("/api/route-and-predict", TestFixtures.routeRequest())
                        .header("Authorization", BEARER + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyId").isNumber())
                .andExpect(jsonPath("$.prediction.roadConditionLabel").value("Moderate"))
                .andExpect(jsonPath("$.features.trafficLevel").value(0.61))
                .andExpect(jsonPath("$.weather.source").value("open-meteo"))
                .andExpect(jsonPath("$.syntheticModel").value(true));

        assertThat(historyRepository.count()).isEqualTo(1);

        mockMvc.perform(get("/api/history").header("Authorization", BEARER + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].originLabel").value(TestFixtures.ORIGIN_LABEL))
                .andExpect(jsonPath("$[0].destinationLabel").value(TestFixtures.DESTINATION_LABEL))
                .andExpect(jsonPath("$[0].predictedCondition").value("Moderate"))
                .andExpect(jsonPath("$[0].selectedDepartureTime").value("08:00"))
                .andExpect(jsonPath("$[0].confidence").value(0.83));
    }

    @Test
    @DisplayName("an anonymous analysis succeeds but is NOT saved")
    void anonymousAnalysisIsNotSaved() throws Exception {
        mockMvc.perform(jsonPost("/api/route-and-predict", TestFixtures.routeRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyId").doesNotExist())
                .andExpect(jsonPath("$.prediction.roadConditionLabel").value("Moderate"));

        assertThat(historyRepository.count())
                .as("an anonymous request must leave no trace in the database")
                .isZero();
    }

    @Test
    @DisplayName("one user never sees another user's history")
    void historyIsScopedToItsOwner() throws Exception {
        AuthResponse mine = signUp("mine@example.dk");
        AuthResponse theirs = signUp("theirs@example.dk");

        mockMvc.perform(jsonPost("/api/route-and-predict", TestFixtures.routeRequest())
                        .header("Authorization", BEARER + mine.token()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/history").header("Authorization", BEARER + theirs.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/history").header("Authorization", BEARER + mine.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("GET /api/history without a token is a 401")
    void historyRequiresALogin() throws Exception {
        mockMvc.perform(get("/api/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the saved row keeps the feature snapshot the model actually saw")
    void savedRowCarriesTheFeatureSnapshot() throws Exception {
        AuthResponse user = signUp("snapshot@example.dk");

        mockMvc.perform(jsonPost("/api/route-and-predict", TestFixtures.routeRequest())
                        .header("Authorization", BEARER + user.token()))
                .andExpect(status().isOk());

        var saved = historyRepository.findAll().get(0);
        assertThat(saved.getFeatureSnapshot()).contains("\"trafficLevel\":0.61");
        assertThat(saved.getWeatherRisk()).isEqualTo(0.38);
        assertThat(saved.getVehicleLoadFactor()).isEqualTo(0.55);
        assertThat(saved.getRouteSummary())
                .isEqualTo(TestFixtures.ORIGIN_LABEL + " to " + TestFixtures.DESTINATION_LABEL);
    }
}
