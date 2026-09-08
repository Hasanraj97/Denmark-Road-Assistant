package com.example.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Makes the H2 web console reachable, but <em>only</em> when developing.
 *
 * <p>The main security configuration used to carry a permanent
 * {@code permitAll} rule for {@code /h2-console/**} and a relaxed frame-options
 * header, while the console itself was never switched on. That combination is
 * worse than useless: it is a loaded gun. Anyone who later set
 * {@code spring.h2.console.enabled=true} - which the old README actively invited,
 * because it claimed H2 was the default - would have exposed an unauthenticated
 * SQL shell over the whole database without changing a line of security code.</p>
 *
 * <p>Now the permission and the console travel together. This class is active
 * only under the {@code dev} profile <em>and</em> only when the console property
 * is genuinely on, so neither half can be enabled by accident.</p>
 *
 * <p>To use it: {@code ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev},
 * then open {@code http://localhost:8080/h2-console} with JDBC URL
 * {@code jdbc:h2:file:./data/road-assistant}, user {@code sa}, empty password.</p>
 */
@Configuration
@Profile("dev")
@ConditionalOnProperty(name = "spring.h2.console.enabled", havingValue = "true")
public class DevH2ConsoleConfig {

    /**
     * Runs before the main API chain so that console requests are handled here
     * and never reach the JWT filter.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/h2-console/**")
                .csrf(csrf -> csrf.disable())
                // The console is built from framesets, so it cannot render with the
                // default DENY. sameOrigin is scoped to this chain alone, unlike the
                // old global setting.
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
