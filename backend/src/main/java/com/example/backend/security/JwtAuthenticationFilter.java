package com.example.backend.security;

import com.example.backend.entity.AppUser;
import com.example.backend.exception.UnauthorizedException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the {@code Authorization: Bearer ...} header and, if the token is good,
 * marks the request as coming from that user.
 *
 * <p>This filter runs before Spring MVC, so {@code @RestControllerAdvice} cannot
 * catch anything it throws. Any exception escaping here reaches the servlet
 * container and becomes a bare HTTP 500 with an HTML error page - which is
 * exactly what used to happen when a user changed their email.</p>
 *
 * <p>So the rule in this class is: <strong>never throw</strong>. Every failure is
 * recorded as a friendly message on the request and the chain continues without
 * an authenticated user. Spring Security's entry point then turns that into a
 * clean 401 carrying the message. A request to a public endpoint with a broken
 * token still succeeds, which is the correct behaviour.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute read by SecurityConfig when it writes the 401 body. */
    public static final String JWT_ERROR_ATTRIBUTE = "jwt_error";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, CustomUserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        try {
            authenticate(request, token);
        } catch (ExpiredJwtException exception) {
            reject(request, "Your session has expired. Please log in again.");
        } catch (UsernameNotFoundException exception) {
            // The token is perfectly valid but the account is gone. This is a 401,
            // not a 500: there is nothing wrong with the server.
            reject(request, exception.getMessage());
        } catch (JwtException | IllegalArgumentException exception) {
            reject(request, "Your session token is invalid. Please log in again.");
        } catch (RuntimeException exception) {
            // Last resort. Something genuinely unexpected happened while reading the
            // token. Log it server-side, but still answer with a clean 401 rather
            // than letting a raw stack trace reach the browser.
            logger.error("Unexpected error while authenticating a bearer token", exception);
            reject(request, "Your session could not be verified. Please log in again.");
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }

        Long userId = jwtService.extractUserId(token);
        if (userId == null) {
            reject(request, "Your session token is invalid. Please log in again.");
            return;
        }

        AppUser user = userDetailsService.loadUserById(userId);
        if (!jwtService.isTokenValid(token, user)) {
            reject(request, "Your session token is invalid. Please log in again.");
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void reject(HttpServletRequest request, String message) {
        request.setAttribute(JWT_ERROR_ATTRIBUTE, new UnauthorizedException(message));
    }
}
