package com.example.backend.auth;

import com.example.backend.support.AbstractApiTest;
import com.example.backend.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signing up and logging in - the four outcomes the frontend has to tell apart.
 */
class AuthFlowTest extends AbstractApiTest {

    @Test
    @DisplayName("signup creates the account and hands back a token and the user")
    void signupSucceeds() throws Exception {
        MvcResult result = mockMvc.perform(jsonPost("/api/auth/signup", TestFixtures.signup("new.driver@example.dk")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("new.driver@example.dk"))
                .andExpect(jsonPath("$.user.fullName").value("Test Driver"))
                .andExpect(jsonPath("$.user.id").isNumber())
                // The password hash must never leave the server.
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();

        assertThat(userRepository.existsByEmailIgnoreCase("new.driver@example.dk")).isTrue();

        // The subject of the issued token is the user id, not the email.
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
        long userId = objectMapper.readTree(result.getResponse().getContentAsString()).path("user").path("id").asLong();
        assertThat(decodePayload(token)).containsEntry("sub", String.valueOf(userId));
    }

    @Test
    @DisplayName("signing up twice with the same email is a 409, not a 500")
    void duplicateEmailIsConflict() throws Exception {
        mockMvc.perform(jsonPost("/api/auth/signup", TestFixtures.signup("taken@example.dk")))
                .andExpect(status().isOk());

        // Different capitalisation on purpose: emails are compared case-insensitively.
        mockMvc.perform(jsonPost("/api/auth/signup", TestFixtures.signup("Taken@Example.dk")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with that email already exists."));

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("login with the right password returns a usable token")
    void loginSucceeds() throws Exception {
        signUp("returning@example.dk");

        MvcResult result = mockMvc.perform(jsonPost("/api/auth/login",
                        TestFixtures.login("returning@example.dk", TestFixtures.PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("returning@example.dk"))
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).path("token").asText();

        // "Usable" means the rest of the API accepts it.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/users/me")
                        .header("Authorization", BEARER + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("returning@example.dk"));
    }

    @Test
    @DisplayName("login with the wrong password is a 401 and says nothing about which half was wrong")
    void wrongPasswordIsUnauthorized() throws Exception {
        signUp("careful@example.dk");

        mockMvc.perform(jsonPost("/api/auth/login",
                        TestFixtures.login("careful@example.dk", "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    @DisplayName("login for an account that does not exist is also a 401")
    void unknownAccountIsUnauthorized() throws Exception {
        mockMvc.perform(jsonPost("/api/auth/login",
                        TestFixtures.login("nobody@example.dk", TestFixtures.PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password."));
    }
}
