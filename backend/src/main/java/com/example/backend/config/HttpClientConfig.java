package com.example.backend.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

/**
 * Puts a time limit on every outgoing HTTP call the backend makes.
 *
 * <p>The geocoding and routing services talk to
 * {@code nominatim.openstreetmap.org} and {@code router.project-osrm.org}. Those
 * are free, shared, best-effort public endpoints with no service guarantee. The
 * services were built from the auto-configured {@code RestClient.Builder} with no
 * timeouts at all, which means the default is to wait forever.</p>
 *
 * <p>In practice that is a way to take the whole backend down without attacking
 * it: if Nominatim stops responding rather than refusing the connection, each
 * analysis request parks a Tomcat worker thread indefinitely. Once every worker
 * is parked the server stops answering anything, including the health check.</p>
 *
 * <p>This customizer is applied by Spring Boot to the {@code RestClient.Builder}
 * that both services already inject, so they pick the limits up without changing
 * their own code. A slow upstream now fails as a normal error the user can read,
 * instead of hanging.</p>
 */
@Configuration
public class HttpClientConfig {

    /** How long to wait for the connection itself to be established. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * How long to wait for the response body. OSRM route requests for long Danish
     * routes are the slowest legitimate call, and they comfortably finish well
     * inside this.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    public RestClientCustomizer restClientTimeoutCustomizer() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(CONNECT_TIMEOUT);
            factory.setReadTimeout(READ_TIMEOUT);
            builder.requestFactory(factory);
        };
    }
}
