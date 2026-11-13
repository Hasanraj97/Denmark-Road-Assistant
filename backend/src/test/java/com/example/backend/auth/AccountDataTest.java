package com.example.backend.auth;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.SignupRequest;
import com.example.backend.support.AbstractApiTest;
import com.example.backend.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.nullValue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Personal data the app holds, after the November 2026 review.
 *
 * <ul>
 *   <li>Phone and address are optional: the app does not need them.</li>
 *   <li>A user can download everything stored about them, without the password hash.</li>
 *   <li>A user can delete their account, and then cannot log in with it again.</li>
 * </ul>
 */
class AccountDataTest extends AbstractApiTest {

    @Test
    @DisplayName("sign-up works with only name, email and password")
    void phoneAndAddressAreOptional() throws Exception {
        SignupRequest minimal = new SignupRequest("Minimal Driver", "minimal@example.dk", null, null,
                TestFixtures.PASSWORD);

        mockMvc.perform(jsonPost("/api/auth/signup", minimal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("minimal@example.dk"))
                .andExpect(jsonPath("$.user.phone").value(nullValue()));
    }

    @Test
    @DisplayName("GET /api/users/me/export returns the profile and saved analyses, never the password")
    void exportContainsProfileButNoPassword() throws Exception {
        AuthResponse user = signUp("export@example.dk");

        mockMvc.perform(get("/api/users/me/export").header("Authorization", BEARER + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.email").value("export@example.dk"))
                .andExpect(jsonPath("$.savedAnalyses").isArray())
                .andExpect(jsonPath("$.profile.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.profile.password").doesNotExist());
    }

    @Test
    @DisplayName("DELETE /api/users/me removes the account, and the old login no longer works")
    void deletingTheAccountRemovesIt() throws Exception {
        AuthResponse user = signUp("leaving@example.dk");

        mockMvc.perform(delete("/api/users/me").header("Authorization", BEARER + user.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(jsonPost("/api/auth/login", TestFixtures.login("leaving@example.dk", TestFixtures.PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("export and delete both need a login")
    void exportAndDeleteNeedALogin() throws Exception {
        mockMvc.perform(get("/api/users/me/export")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/users/me")).andExpect(status().isUnauthorized());
    }
}
