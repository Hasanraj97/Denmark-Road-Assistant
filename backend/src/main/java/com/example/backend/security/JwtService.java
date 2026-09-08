package com.example.backend.security;

import com.example.backend.config.AppProperties;
import com.example.backend.entity.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

/**
 * Creates and reads the JSON Web Tokens that keep a user logged in.
 *
 * <p><strong>The subject of the token is the user's database id, not their
 * email.</strong> That is a deliberate change. The token used to carry the email
 * as its subject, and every request looked the user up by that email. So the
 * moment somebody changed their email on the profile page, their own token
 * pointed at an address that no longer existed, the lookup failed, and the app
 * threw them out with a 500 error.</p>
 *
 * <p>A database id never changes, so keying the token on the id makes that whole
 * class of bug impossible. The email is still carried as an ordinary claim
 * because it is useful when reading a token by hand, but nothing authenticates
 * on it.</p>
 */
@Service
public class JwtService {

    /** Claim holding the user's email. Informational only - never used for lookup. */
    private static final String EMAIL_CLAIM = "email";

    /** Claim holding the user's display name, used by the frontend header. */
    private static final String FULL_NAME_CLAIM = "fullName";

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(SecretKey jwtSigningKey, AppProperties appProperties) {
        this.signingKey = jwtSigningKey;
        this.expirationMs = appProperties.jwtExpirationMs();
    }

    public String generateToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim(EMAIL_CLAIM, user.getEmail())
                .claim(FULL_NAME_CLAIM, user.getFullName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Returns the user id carried by the token.
     *
     * @throws io.jsonwebtoken.JwtException if the token is unreadable, unsigned,
     *         signed with the wrong key, expired, or its subject is not a number.
     */
    public Long extractUserId(String token) {
        String subject = extractClaims(token).getSubject();
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException ex) {
            // Tokens issued by the old email-subject scheme land here. They are
            // treated as invalid rather than as a server error, so the user is
            // simply asked to log in again.
            throw new io.jsonwebtoken.MalformedJwtException(
                    "Token subject is not a user id. It was probably issued by an older version of this app.", ex);
        }
    }

    public String extractEmail(String token) {
        return extractClaims(token).get(EMAIL_CLAIM, String.class);
    }

    /**
     * Confirms the token really belongs to this user and has not expired.
     */
    public boolean isTokenValid(String token, AppUser user) {
        Claims claims = extractClaims(token);
        return String.valueOf(user.getId()).equals(claims.getSubject())
                && claims.getExpiration().after(new Date());
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
