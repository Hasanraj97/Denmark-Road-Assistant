package com.example.backend.api;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.ModelInfoDto;
import com.example.backend.support.AbstractApiTest;
import com.example.backend.support.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may call what, and what the public endpoints are allowed to reveal.
 *
 * <p>Two separate findings are covered.</p>
 *
 * <p><strong>Retraining used to be public.</strong> {@code POST /api/retrain-model}
 * regenerates a 12,000-row dataset and fits a several-hundred-tree forest,
 * overwriting the model on disk. An anonymous caller could loop it and both
 * exhaust the machine and change everybody else's predictions. It now requires a
 * login.</p>
 *
 * <p><strong>Defect S3: {@code GET /api/model-info} leaked filesystem paths.</strong>
 * The response used to carry {@code datasetPath} and {@code modelPath} - absolute
 * paths from the original developer's machine - to any anonymous caller. The test
 * below checks the whole JSON tree, at any depth, so re-adding the field under a
 * nested object would still fail.</p>
 */
class SecurityRulesTest extends AbstractApiTest {

    private static final List<String> FORBIDDEN_KEYS = List.of("datasetPath", "modelPath", "dataset_path", "model_path");

    @Test
    @DisplayName("POST /api/retrain-model anonymously -> 401, and the model is never touched")
    void retrainRequiresALogin() throws Exception {
        mockMvc.perform(post("/api/retrain-model"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("You need to be logged in to do that."));

        verify(mlServiceClient, never()).retrain();
    }

    @Test
    @DisplayName("POST /api/retrain-model with a garbage token -> 401, not 500")
    void retrainWithBrokenTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/retrain-model").header("Authorization", BEARER + "nonsense"))
                .andExpect(status().isUnauthorized());

        verify(mlServiceClient, never()).retrain();
    }

    @Test
    @DisplayName("POST /api/retrain-model as a signed-in user -> 200")
    void retrainIsAllowedForASignedInUser() throws Exception {
        AuthResponse user = signUp("trainer@example.dk");
        when(mlServiceClient.retrain()).thenReturn(TestFixtures.modelInfo());

        mockMvc.perform(post("/api/retrain-model").header("Authorization", BEARER + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value("RandomForestClassifier"));

        verify(mlServiceClient).retrain();
    }

    @Test
    @DisplayName("GET /api/model-info is public and leaks no filesystem paths")
    void modelInfoIsPublicAndCarriesNoPaths() throws Exception {
        when(mlServiceClient.getModelInfo()).thenReturn(TestFixtures.modelInfo());

        MvcResult result = mockMvc.perform(get("/api/model-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelName").value("RandomForestClassifier"))
                .andExpect(jsonPath("$.sampleCount").value(12000))
                .andExpect(jsonPath("$.datasetPath").doesNotExist())
                .andExpect(jsonPath("$.modelPath").doesNotExist())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        List<String> keys = new ArrayList<>();
        collectKeys(objectMapper.readTree(body), keys);
        assertThat(keys)
                .as("the public model-info response must not expose where files live on the server")
                .doesNotContainAnyElementsOf(FORBIDDEN_KEYS);

        // Belt and braces: no absolute path shaped like C:\... or /home/... either.
        assertThat(body).doesNotContainPattern("[A-Za-z]:\\\\\\\\");
        assertThat(body).doesNotContain("ml-service/data");
    }

    @Test
    @DisplayName("the ModelInfoDto record itself has no path components")
    void modelInfoRecordHasNoPathFields() {
        List<String> components = new ArrayList<>();
        for (var component : ModelInfoDto.class.getRecordComponents()) {
            components.add(component.getName());
        }
        assertThat(components).doesNotContainAnyElementsOf(FORBIDDEN_KEYS);
    }

    @Test
    @DisplayName("the public demo endpoints really are public")
    void demoEndpointsStayPublic() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an endpoint nobody listed is private by default")
    void unknownApiPathIsNotSilentlyPublic() throws Exception {
        // The chain ends in anyRequest().authenticated(), so an unlisted path must
        // answer 401 to an anonymous caller rather than falling through to a 404
        // that would prove it was reachable.
        mockMvc.perform(get("/api/some-endpoint-added-tomorrow"))
                .andExpect(status().isUnauthorized());
    }

    private static void collectKeys(JsonNode node, List<String> into) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                into.add(name);
                collectKeys(node.get(name), into);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, into));
        }
    }
}
