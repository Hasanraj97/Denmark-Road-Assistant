package com.example.backend.config;

import com.example.backend.dto.ApiErrorResponse;
import com.example.backend.exception.UnauthorizedException;
import com.example.backend.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Decides who may call which endpoint.
 *
 * <p>Three deliberate changes from the original configuration:</p>
 *
 * <p><strong>The chain is now default-deny.</strong> It used to end with
 * {@code anyRequest().permitAll()}, which meant every endpoint added in future
 * would be public unless somebody remembered to list it. It now ends with
 * {@code anyRequest().authenticated()}, so a new endpoint is private until it is
 * deliberately opened.</p>
 *
 * <p><strong>Retraining requires a login.</strong> {@code POST /api/retrain-model}
 * used to be public. It regenerates a 12,000-row dataset and fits a
 * several-hundred-tree forest, overwriting the dataset and model files on disk,
 * so an anonymous caller could flatten the server in a loop and change everyone
 * else's predictions. Route analysis itself stays public, because a supervisor
 * must be able to demonstrate the app without creating an account.</p>
 *
 * <p><strong>The H2 console rule moved out.</strong> A {@code permitAll} rule for
 * {@code /h2-console/**} and a relaxed frame-options setting were both present
 * while the console itself was switched off - dead configuration that would have
 * silently become an unauthenticated SQL shell the moment anyone enabled it. The
 * console and its security rule now live together in {@link DevH2ConsoleConfig},
 * which only exists under the {@code dev} profile.</p>
 *
 * <p>There is also no longer an explicit {@code DaoAuthenticationProvider} bean.
 * It used a constructor deprecated in Spring Security 6.4, it produced a startup
 * warning, and nothing ever called it: passwords are checked directly in
 * {@code AuthService.login} and every later request is authenticated by the JWT
 * filter. Spring Security wires an equivalent provider from the
 * {@code UserDetailsService} bean on its own if it is ever needed.</p>
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // The API is stateless and token-based, so there is no session
                // cookie for a CSRF attack to ride on.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // --- Public: signing up and logging in ---
                        .requestMatchers("/api/auth/**").permitAll()

                        // --- Public: liveness check ---
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()

                        // --- Public: the demo flow a supervisor needs ---
                        // These are read-only. They cost a geocode, a route lookup
                        // and one prediction, and they change nothing on disk.
                        .requestMatchers(HttpMethod.GET, "/api/model-info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/route", "/api/predict", "/api/route-and-predict")
                        .permitAll()

                        // --- Private: expensive and state-changing ---
                        .requestMatchers(HttpMethod.POST, "/api/retrain-model").authenticated()

                        // --- Private: a user's own data ---
                        .requestMatchers("/api/history/**").authenticated()
                        .requestMatchers("/api/users/**").authenticated()

                        // Anything not listed above is private by default.
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeUnauthorized(
                                        response,
                                        request.getAttribute(JwtAuthenticationFilter.JWT_ERROR_ATTRIBUTE),
                                        authException))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Writes the 401 body. If the JWT filter recorded a specific reason - expired
     * token, deleted account, malformed token - the user is told that reason
     * rather than a generic "authentication required".
     */
    private void writeUnauthorized(HttpServletResponse response, Object jwtError, AuthenticationException authException)
            throws IOException {
        String message = jwtError instanceof UnauthorizedException unauthorizedException
                ? unauthorizedException.getMessage()
                : "You need to be logged in to do that.";

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(new ApiErrorResponse(message, Instant.now())));
    }
}
