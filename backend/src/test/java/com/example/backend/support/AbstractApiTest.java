package com.example.backend.support;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.WeatherReadingDto;
import com.example.backend.ml.MlServiceClient;
import com.example.backend.repository.AppUserRepository;
import com.example.backend.repository.RouteAnalysisHistoryRepository;
import com.example.backend.repository.RouteFeedbackRepository;
import com.example.backend.routing.GeocodingService;
import com.example.backend.routing.OsrmRoutingService;
import com.example.backend.weather.WeatherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared setup for every HTTP-level test.
 *
 * <p>Three rules are enforced here rather than repeated in each test:</p>
 *
 * <ul>
 *   <li><strong>Nothing talks to the network.</strong> The four collaborators
 *       that make outgoing calls - Nominatim, OSRM, Open-Meteo and the Python ML
 *       service - are replaced with Mockito mocks. Everything else in the
 *       application (security chain, controllers, JPA, the exception handler) is
 *       the real thing, because those are exactly the parts the defects lived
 *       in.</li>
 *   <li><strong>The signing key is fixed and injected.</strong> Tests can
 *       therefore mint tokens the running application will accept, including a
 *       token that expired in the past.</li>
 *   <li><strong>Each test starts with an empty database.</strong> The context is
 *       shared across test classes for speed, so the tables are emptied before
 *       every test method instead of relying on a fresh schema.</li>
 * </ul>
 *
 * <p>{@code @MockitoBean} is used rather than the older {@code @MockBean}, which
 * is deprecated for removal from Spring Boot 3.4 onwards.</p>
 */
@SpringBootTest(properties = {
        // Also set inline so an APP_JWT_SECRET exported in the developer's shell
        // cannot change the key under the tests: inlined test properties outrank
        // OS environment variables.
        "app.jwt-secret=" + AbstractApiTest.TEST_JWT_SECRET
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractApiTest {

    public static final String TEST_JWT_SECRET = "denmark-road-assistant-fixed-test-signing-key-0123456789";

    protected static final String BEARER = "Bearer ";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected AppUserRepository userRepository;

    @Autowired
    protected RouteAnalysisHistoryRepository historyRepository;

    @Autowired
    protected RouteFeedbackRepository feedbackRepository;

    /** The very key the application signs with - see JwtKeyConfig. */
    @Autowired
    protected SecretKey jwtSigningKey;

    @MockitoBean
    protected GeocodingService geocodingService;

    @MockitoBean
    protected OsrmRoutingService routingService;

    @MockitoBean
    protected MlServiceClient mlServiceClient;

    @MockitoBean
    protected WeatherService weatherService;

    @BeforeEach
    void resetState() {
        feedbackRepository.deleteAll();
        historyRepository.deleteAll();
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();

        // A harmless default so the real RouteObservationService never reaches
        // Open-Meteo. Individual tests may override it.
        Mockito.lenient()
                .when(weatherService.forecastFor(anyDouble(), anyDouble(), any(LocalDateTime.class)))
                .thenReturn(TestFixtures.weather());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    protected <T> T read(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type);
    }

    protected MockHttpServletRequestBuilder jsonPost(String url, Object body) throws Exception {
        return post(url).contentType(APPLICATION_JSON).content(json(body));
    }

    /** Registers a user through the real signup endpoint and returns token + user. */
    protected AuthResponse signUp(String email) throws Exception {
        MvcResult result = mockMvc.perform(jsonPost("/api/auth/signup", TestFixtures.signup(email)))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException("Signup failed in test setup: "
                    + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString());
        }
        return read(result, AuthResponse.class);
    }

    /**
     * Signs a token with the application's own key but an expiry in the past.
     * This is how the "your session has expired" branch is reached without
     * waiting for a real token to age out.
     */
    protected String expiredTokenFor(long userId, String email) {
        Instant issued = Instant.now().minus(2, ChronoUnit.DAYS);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("fullName", "Expired Session")
                .issuedAt(Date.from(issued))
                .expiration(Date.from(issued.plus(1, ChronoUnit.HOURS)))
                .signWith(jwtSigningKey)
                .compact();
    }

    /**
     * A correctly signed, unexpired token whose subject is an EMAIL - the scheme
     * this project used before the profile-email defect was fixed.
     */
    protected String legacyEmailSubjectToken(String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
                .signWith(jwtSigningKey)
                .compact();
    }

    /** Reads the claims out of a token without verifying it, for assertions. */
    protected Map<String, Object> decodePayload(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Not a JWT: " + token);
        }
        byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = objectMapper.readValue(payload, Map.class);
        return claims;
    }

    protected static WeatherReadingDto liveWeather() {
        return TestFixtures.weather();
    }
}
