package com.example.backend.auth;

import com.example.backend.dto.AuthResponse;
import com.example.backend.support.AbstractApiTest;
import com.example.backend.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Defect <strong>F2</strong>, the whole flow end to end.
 *
 * <p>The original app put the user's <em>email</em> in the JWT subject and looked
 * the user up by that email on every request. Changing your email on the profile
 * page therefore logged you out of your own account: the browser kept sending a
 * token naming an address that no longer existed, the lookup failed inside a
 * servlet filter where the exception handler could not reach it, and the user
 * got a bare 500.</p>
 *
 * <p>The fix has two halves and this test pins both:</p>
 * <ol>
 *   <li>the subject is the database id, which never changes - so the token the
 *       browser already holds keeps working after the email changes;</li>
 *   <li>{@code PUT /api/users/me} returns a freshly signed token, so the
 *       {@code email} and {@code fullName} claims inside it stay honest.</li>
 * </ol>
 *
 * <p>Putting the email back as the subject makes
 * {@link #originalTokenStillWorksAfterEmailChange()} fail.</p>
 */
class ProfileEmailChangeTest extends AbstractApiTest {

    @Test
    @DisplayName("changing your email returns a NEW token and the old one still works")
    void originalTokenStillWorksAfterEmailChange() throws Exception {
        AuthResponse signedUp = signUp("before@example.dk");
        String originalToken = signedUp.token();
        Long userId = signedUp.user().id();

        // --- change the email, authenticating with the ORIGINAL token ---
        MvcResult updateResult = mockMvc.perform(put("/api/users/me")
                        .header("Authorization", BEARER + originalToken)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json(TestFixtures.profileUpdate("Renamed Driver", "after@example.dk"))))
                .andExpect(status().isOk())
                // (a) the response is an AuthResponse carrying a fresh token,
                //     not merely the updated user.
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("after@example.dk"))
                .andExpect(jsonPath("$.user.fullName").value("Renamed Driver"))
                .andExpect(jsonPath("$.user.id").value(userId.intValue()))
                .andReturn();

        AuthResponse updated = read(updateResult, AuthResponse.class);
        assertThat(updated.token())
                .as("PUT /api/users/me must hand back a re-signed token")
                .isNotBlank()
                .isNotEqualTo(originalToken);

        // (b) THE POINT OF THE TEST. The browser has not stored the new token
        //     yet - it is still sending the one it got at signup. That must keep
        //     working, because the token identifies the user by id.
        mockMvc.perform(get("/api/users/me").header("Authorization", BEARER + originalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("after@example.dk"))
                .andExpect(jsonPath("$.id").value(userId.intValue()));

        // And the newly issued token works too, with up-to-date claims in it.
        mockMvc.perform(get("/api/users/me").header("Authorization", BEARER + updated.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("after@example.dk"));

        assertThat(decodePayload(updated.token()))
                .containsEntry("email", "after@example.dk")
                .containsEntry("fullName", "Renamed Driver");
    }

    @Test
    @DisplayName("the JWT subject is the user id, not the email")
    void tokenSubjectIsTheUserId() throws Exception {
        AuthResponse signedUp = signUp("subject@example.dk");

        Object subject = decodePayload(signedUp.token()).get("sub");

        assertThat(subject)
                .as("the subject must be the immutable user id - an email here is defect F2")
                .isEqualTo(String.valueOf(signedUp.user().id()));
        assertThat(String.valueOf(subject))
                .as("the subject must not be an email address")
                .doesNotContain("@")
                .matches("\\d+");
    }

    @Test
    @DisplayName("you can log in with the new email after changing it")
    void loginWithTheNewEmailWorks() throws Exception {
        AuthResponse signedUp = signUp("old.address@example.dk");

        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", BEARER + signedUp.token())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json(TestFixtures.profileUpdate("Test Driver", "new.address@example.dk"))))
                .andExpect(status().isOk());

        mockMvc.perform(jsonPost("/api/auth/login",
                        TestFixtures.login("new.address@example.dk", TestFixtures.PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("new.address@example.dk"));

        mockMvc.perform(jsonPost("/api/auth/login",
                        TestFixtures.login("old.address@example.dk", TestFixtures.PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("taking someone else's email is a 409, and nothing is changed")
    void cannotTakeAnotherUsersEmail() throws Exception {
        signUp("first.owner@example.dk");
        AuthResponse second = signUp("second.owner@example.dk");

        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", BEARER + second.token())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json(TestFixtures.profileUpdate("Squatter", "first.owner@example.dk"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("That email is already used by another account."));

        mockMvc.perform(get("/api/users/me").header("Authorization", BEARER + second.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("second.owner@example.dk"));
    }

    @Test
    @DisplayName("updating a profile without a token is a 401")
    void anonymousProfileUpdateIsRejected() throws Exception {
        mockMvc.perform(put("/api/users/me")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json(TestFixtures.profileUpdate("Nobody", "nobody@example.dk"))))
                .andExpect(status().isUnauthorized());
    }
}
