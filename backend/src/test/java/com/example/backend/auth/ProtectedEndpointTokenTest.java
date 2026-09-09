package com.example.backend.auth;

import com.example.backend.dto.AuthResponse;
import com.example.backend.support.AbstractApiTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How {@code GET /api/users/me} answers a bad or missing bearer token.
 *
 * <p>This is defect <strong>F2</strong> seen from the outside. The JWT filter is
 * a servlet filter: it runs before Spring MVC, so anything it throws escapes the
 * {@code @RestControllerAdvice} entirely and reaches the servlet container as a
 * raw HTTP 500 with an HTML body. The old code let a broken token do exactly
 * that, and the browser - which only knows how to react to a 401 - showed a
 * generic crash instead of asking the user to log in again.</p>
 *
 * <p>Every case below therefore asserts <em>401 with a readable JSON message</em>.
 * A 500 here is the bug.</p>
 */
class ProtectedEndpointTokenTest extends AbstractApiTest {

    @Test
    @DisplayName("no token at all -> 401")
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("You need to be logged in to do that."));
    }

    @Test
    @DisplayName("a garbage token -> 401, never 500")
    void garbageTokenIsUnauthorizedNotServerError() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", BEARER + "this-is-not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Your session token is invalid. Please log in again."));
    }

    @Test
    @DisplayName("a token that looks like a JWT but is signed with another key -> 401")
    void wrongSignatureIsUnauthorized() throws Exception {
        // Structurally a JWT (three dot-separated base64url segments) but the
        // signature does not verify against the application's key.
        String forged = "eyJhbGciOiJIUzI1NiJ9"
                + ".eyJzdWIiOiIxIiwiZXhwIjo0MTAyNDQ0ODAwfQ"
                + ".ZmFrZS1zaWduYXR1cmUtdGhhdC1kb2VzLW5vdC12ZXJpZnk";

        mockMvc.perform(get("/api/users/me").header("Authorization", BEARER + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session token is invalid. Please log in again."));
    }

    @Test
    @DisplayName("an expired token -> 401 that says the session expired")
    void expiredTokenIsUnauthorizedWithExpiryMessage() throws Exception {
        AuthResponse signedUp = signUp("expired@example.dk");

        // Signed with the SAME key the application uses, but its expiry is in the
        // past - so it fails on age alone, not on the signature.
        String expired = expiredTokenFor(signedUp.user().id(), signedUp.user().email());

        mockMvc.perform(get("/api/users/me").header("Authorization", BEARER + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has expired. Please log in again."));
    }

    @Test
    @DisplayName("a token in the OLD email-subject format -> 401, not a 500")
    void legacyEmailSubjectTokenIsUnauthorized() throws Exception {
        signUp("legacy@example.dk");

        // Perfectly signed and unexpired, but its subject is an email address.
        // Tokens like this were issued by the version of the app that had the
        // profile-email bug; they must be refused politely, not crash the server.
        String legacy = legacyEmailSubjectToken("legacy@example.dk");

        mockMvc.perform(get("/api/users/me").header("Authorization", BEARER + legacy))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session token is invalid. Please log in again."));
    }

    @Test
    @DisplayName("a valid token for a user who has since been deleted -> 401")
    void deletedAccountIsUnauthorized() throws Exception {
        AuthResponse signedUp = signUp("ghost@example.dk");
        userRepository.deleteById(signedUp.user().id());

        mockMvc.perform(get("/api/users/me").header("Authorization", BEARER + signedUp.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("The account this session belongs to no longer exists."));
    }

    @Test
    @DisplayName("a good token -> 200 with the caller's own profile")
    void validTokenReachesTheEndpoint() throws Exception {
        AuthResponse signedUp = signUp("good@example.dk");

        mockMvc.perform(get("/api/users/me").header("Authorization", BEARER + signedUp.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("good@example.dk"))
                .andExpect(jsonPath("$.id").value(signedUp.user().id().intValue()));

        assertThat(signedUp.token()).isNotBlank();
    }

    @Test
    @DisplayName("a broken token on a PUBLIC endpoint does not break the request")
    void brokenTokenOnPublicEndpointStillWorks() throws Exception {
        // The filter must record the problem and carry on rather than throwing,
        // so an anonymous-but-corrupt caller can still use the public demo flow.
        mockMvc.perform(get("/api/health").header("Authorization", BEARER + "still-not-a-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
