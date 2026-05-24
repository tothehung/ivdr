package com.ivdr.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Provides JWT creation and validation services for the IVDR application.
 *
 * <p>Uses jjwt 0.12.x API exclusively — no deprecated method calls.
 * Tokens are signed with HMAC-SHA-512 (HS512).
 *
 * <p>Required application properties (under {@code app.jwt.*}):
 * <ul>
 *   <li>{@code app.jwt.secret}              — Base64-or-plain secret, ≥ 64 bytes recommended for HS512</li>
 *   <li>{@code app.jwt.access-expiry-ms}    — Access token TTL in milliseconds  (e.g. 900000 = 15 min)</li>
 *   <li>{@code app.jwt.refresh-expiry-ms}   — Refresh token TTL in milliseconds (e.g. 604800000 = 7 days)</li>
 * </ul>
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    // Custom claim key names stored inside the JWT payload
    private static final String CLAIM_ORG_ID  = "orgId";
    private static final String CLAIM_EMAIL   = "email";
    private static final String CLAIM_ROLE    = "role";

    /** The signing key derived once from the configured secret. */
    private final SecretKey signingKey;
    private final long accessExpiryMs;
    private final long refreshExpiryMs;

    /**
     * Constructor-based injection so that the signing key is derived exactly once
     * at startup rather than on every token operation.
     *
     * @param secret           raw secret string (at least 64 ASCII chars for HS512)
     * @param accessExpiryMs   access token lifetime in milliseconds
     * @param refreshExpiryMs  refresh token lifetime in milliseconds
     */
    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-expiry-ms}") long accessExpiryMs,
            @Value("${app.jwt.refresh-expiry-ms}") long refreshExpiryMs) {

        // Keys.hmacShaKeyFor() selects the right HMAC algorithm based on key length.
        // For HS512 the key must be ≥ 512 bits (64 bytes).
        this.signingKey      = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiryMs  = accessExpiryMs;
        this.refreshExpiryMs = refreshExpiryMs;
    }

    public long getAccessTokenExpirySeconds() {
        return accessExpiryMs / 1000;
    }

    public long getRefreshTokenExpirySeconds() {
        return refreshExpiryMs / 1000;
    }

    // -------------------------------------------------------------------------
    // Token generation
    // -------------------------------------------------------------------------

    /**
     * Generates a short-lived access token for the given principal.
     *
     * <p>Claims included:
     * <ul>
     *   <li>{@code sub}   — userId (UUID string)</li>
     *   <li>{@code orgId} — organizationId (UUID string)</li>
     *   <li>{@code email} — user e-mail address</li>
     *   <li>{@code role}  — user role (e.g. "ADMIN", "USER")</li>
     * </ul>
     *
     * @param principal authenticated user details
     * @return compact, URL-safe JWT string
     */
    public String generateAccessToken(UserPrincipal principal) {
        return buildToken(principal, accessExpiryMs);
    }

    /**
     * Generates a long-lived refresh token for the given principal.
     * Contains the same claims as the access token so that a new access token
     * can be issued without a database round-trip.
     *
     * @param principal authenticated user details
     * @return compact, URL-safe JWT string
     */
    public String generateRefreshToken(UserPrincipal principal) {
        return buildToken(principal, refreshExpiryMs);
    }

    /**
     * Shared token-building logic for both access and refresh tokens.
     */
    private String buildToken(UserPrincipal principal, long expiryMs) {
        Instant now    = Instant.now();
        Instant expiry = now.plusMillis(expiryMs);

        return Jwts.builder()
                // Standard registered claims
                .subject(principal.userId().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                // Private / custom claims
                .claim(CLAIM_ORG_ID, principal.organizationId().toString())
                .claim(CLAIM_EMAIL,  principal.email())
                .claim(CLAIM_ROLE,   principal.role())
                // Sign with HS512; jjwt 0.12 picks the algorithm from the key type
                .signWith(signingKey, Jwts.SIG.HS512)
                .compact();
    }

    // -------------------------------------------------------------------------
    // Token validation
    // -------------------------------------------------------------------------

    /**
     * Validates a JWT string.
     *
     * <p>Internally parses the token (which verifies signature and expiry) and
     * catches all {@link JwtException} sub-types as well as {@link IllegalArgumentException}.
     *
     * @param token JWT compact string
     * @return {@code true} if the token is syntactically valid, correctly signed, and not expired
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException ex) {
            log.debug("JWT validation failed (JwtException): {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.debug("JWT validation failed (blank token): {}", ex.getMessage());
        } catch (Exception ex) {
            log.warn("JWT validation failed (unexpected): {}", ex.getMessage());
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Claim extraction
    // -------------------------------------------------------------------------

    /**
     * Extracts the {@code sub} claim and returns it as a {@link UUID}.
     *
     * @param token validated JWT compact string
     * @return user ID
     */
    public UUID getUserIdFromToken(String token) {
        return UUID.fromString(getClaimsFromToken(token).getSubject());
    }

    /**
     * Extracts the {@code orgId} claim and returns it as a {@link UUID}.
     *
     * @param token validated JWT compact string
     * @return organisation ID
     */
    public UUID getOrganizationIdFromToken(String token) {
        return UUID.fromString(getClaimsFromToken(token).get(CLAIM_ORG_ID, String.class));
    }

    /**
     * Parses and returns the full {@link Claims} payload from the token.
     * The token must already have been validated; this method will re-throw
     * any {@link JwtException} if called on an invalid token.
     *
     * @param token JWT compact string
     * @return parsed claims
     */
    public Claims getClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
