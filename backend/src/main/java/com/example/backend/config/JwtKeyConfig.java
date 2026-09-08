package com.example.backend.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Decides which key signs and verifies JSON Web Tokens.
 *
 * <p>The project used to keep a real-looking fallback secret in
 * {@code application.properties}. Because that file is committed, the signing key
 * was public knowledge, and anybody who read the repository could mint a token
 * for any account. The fallback is gone. This class replaces it with three
 * explicit rules:</p>
 *
 * <ol>
 *   <li>If {@code APP_JWT_SECRET} is set and long enough, that key is used.</li>
 *   <li>If it is not set and the application is running in a production profile,
 *       startup FAILS with a message that says exactly what to do.</li>
 *   <li>Otherwise (local development) a random key is generated for this run, and
 *       a warning explains that tokens will stop working after a restart.</li>
 * </ol>
 *
 * <p>Rule 3 is what lets a supervisor clone the project and run it with no setup,
 * while rule 2 is what stops that convenience reaching a real deployment.</p>
 */
@Configuration
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    /**
     * HS256 requires a key of at least 256 bits. A shorter secret makes the token
     * cheap to brute-force, so it is rejected rather than silently padded.
     */
    private static final int MINIMUM_SECRET_LENGTH = 32;

    /** Profiles that must never fall back to a generated key. */
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production", "staging");

    /**
     * Secrets that used to be committed to this repository. They are public, so
     * they are refused outright even if somebody still has them in a .env file.
     */
    private static final List<String> KNOWN_LEAKED_SECRETS = List.of(
            "change-this-demo-secret-key-change-this-demo-secret-key"
    );

    @Bean
    public SecretKey jwtSigningKey(AppProperties appProperties, Environment environment) {
        String configuredSecret = appProperties.jwtSecret() == null ? "" : appProperties.jwtSecret().trim();
        boolean production = isProductionProfile(environment);

        if (KNOWN_LEAKED_SECRETS.contains(configuredSecret)) {
            throw new IllegalStateException(
                    "APP_JWT_SECRET is set to the old demo secret that was committed to this repository. "
                            + "That value is public, so tokens signed with it can be forged. "
                            + "Generate a new one, for example with:  openssl rand -base64 48");
        }

        if (!configuredSecret.isEmpty()) {
            if (configuredSecret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_LENGTH) {
                throw new IllegalStateException(
                        "APP_JWT_SECRET is too short. It must be at least " + MINIMUM_SECRET_LENGTH
                                + " characters so the signing key is a full 256 bits. "
                                + "Generate one with:  openssl rand -base64 48");
            }
            log.info("JWT signing key loaded from APP_JWT_SECRET.");
            return Keys.hmacShaKeyFor(configuredSecret.getBytes(StandardCharsets.UTF_8));
        }

        if (production) {
            throw new IllegalStateException(
                    "APP_JWT_SECRET is not set, and the active profile ("
                            + String.join(",", environment.getActiveProfiles())
                            + ") is a production profile. Refusing to start with a generated key, because "
                            + "every restart would invalidate all tokens and the key would never be under "
                            + "your control. Set APP_JWT_SECRET to a random value of at least "
                            + MINIMUM_SECRET_LENGTH + " characters, for example:  openssl rand -base64 48");
        }

        log.warn("""
                ------------------------------------------------------------------
                APP_JWT_SECRET is not set, so a RANDOM signing key was generated
                for this run only. This is fine for local development.

                What it means for you: everyone stays logged in until you restart
                the backend. After a restart, log in again.

                To keep sessions across restarts, set APP_JWT_SECRET (see
                .env.example) to any random string of 32 characters or more.
                ------------------------------------------------------------------""");
        return Jwts.SIG.HS256.key().build();
    }

    private boolean isProductionProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(PRODUCTION_PROFILES::contains);
    }
}
