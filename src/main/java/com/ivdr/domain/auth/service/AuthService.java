package com.ivdr.domain.auth.service;

import com.ivdr.common.exception.ApiException;
import com.ivdr.common.util.CryptoUtil;
import com.ivdr.domain.auth.dto.AuthDtos;
import com.ivdr.domain.auth.dto.AuthDtos.*;
import com.ivdr.domain.auth.entity.Organization;
import com.ivdr.domain.auth.entity.RefreshToken;
import com.ivdr.domain.auth.entity.User;
import com.ivdr.domain.auth.repository.OrganizationRepository;
import com.ivdr.domain.auth.repository.RefreshTokenRepository;
import com.ivdr.domain.auth.repository.UserRepository;
import com.ivdr.security.JwtTokenProvider;
import com.ivdr.security.UserPrincipal;
import com.ivdr.domain.audit.producer.AuditEventProducer;
import com.ivdr.domain.audit.event.AuditEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core authentication service for the IVDR platform.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>New tenant/user registration</li>
 *   <li>Credential validation and JWT issuance</li>
 *   <li>Access-token refresh via a stored refresh-token hash</li>
 *   <li>Logout / refresh-token revocation</li>
 *   <li>Brute-force protection: account lock after 5 consecutive failures</li>
 * </ul>
 *
 * <h2>Security notes</h2>
 * <ul>
 *   <li>Passwords are hashed with BCrypt (strength 12) before storage.</li>
 *   <li>Only a BCrypt hash of the refresh token is stored in the DB — the raw
 *       token is ephemeral and must be delivered to the client once and kept
 *       client-side.</li>
 *   <li>On five consecutive failed login attempts the account is locked for
 *       {@value #LOCK_DURATION_MINUTES} minutes.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Number of consecutive failures before an account is temporarily locked. */
    private static final int MAX_FAILED_ATTEMPTS = 5;

    /** How long (minutes) an account stays locked after the threshold is reached. */
    private static final int LOCK_DURATION_MINUTES = 15;

    /** Pattern used to slugify an organisation name. */
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final OrganizationRepository organizationRepository;
    private final UserRepository         userRepository;
    private final PasswordEncoder         passwordEncoder;
    private final JwtTokenProvider       jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final CryptoUtil             cryptoUtil;
    private final AuditEventProducer     auditEventProducer;

    @Value("${app.jwt.refresh-expiry-ms}")
    private long refreshExpiryMs;

    private void publishAuthEvent(AuditEventType type, User user, java.util.Map<String, Object> metadata) {
        try {
            com.ivdr.domain.audit.event.AuditEvent event = com.ivdr.domain.audit.event.AuditEvent.of(
                    type,
                    user.getOrganization() != null ? user.getOrganization().getId() : null,
                    user.getId(),
                    "user",
                    user.getId().toString(),
                    metadata
            );
            auditEventProducer.publishAuditEvent(event);
        } catch (Exception ex) {
            log.error("Failed to publish auth audit event type={}: {}", type, ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers a new organisation (tenant) and its first admin user.
     *
     * <ol>
     *   <li>Derives a unique slug from {@link RegisterRequest#organizationName()}.</li>
     *   <li>Persists the {@link Organization}.</li>
     *   <li>Persists the admin {@link User} with role {@code ADMIN}.</li>
     *   <li>Issues and returns a {@link TokenResponse}.</li>
     * </ol>
     *
     * @param req validated registration payload
     * @return token bundle for the newly created user
     * @throws IllegalArgumentException if the derived slug is already in use
     */
    @Transactional
    public TokenResponse register(RegisterRequest req) {
        // --- 1. Derive and validate slug ----------------------------------------
        String slug = slugify(req.organizationName());
        if (organizationRepository.existsBySlug(slug)) {
            // Append a short random suffix to guarantee uniqueness.
            slug = slug + "-" + UUID.randomUUID().toString().substring(0, 6);
        }

        // --- 2. Create and persist Organisation ----------------------------------
        Organization org = Organization.builder()
                .name(req.organizationName())
                .slug(slug)
                .plan("free")
                .isActive(true)
                .build();
        org = organizationRepository.save(org);

        log.info("Registered new organisation: id={}, slug={}", org.getId(), org.getSlug());

        // --- 3. Create and persist User ------------------------------------------
        User user = User.builder()
                .organization(org)
                .email(req.email().toLowerCase(Locale.ROOT))
                .passwordHash(passwordEncoder.encode(req.password()))
                .fullName(req.fullName())
                .role("ADMIN")
                .isActive(true)
                .build();
        user = userRepository.save(user);

        log.info("Registered new admin user: id={}, email={}", user.getId(), user.getEmail());

        // Publish USER_REGISTERED audit event
        publishAuthEvent(AuditEventType.USER_REGISTERED, user, java.util.Map.of("email", user.getEmail(), "role", user.getRole()));

        // --- 4. Issue tokens -----------------------------------------------------
        TokenResponse response = buildTokenResponse(user);
        storeRefreshToken(user, response.refreshToken());
        return response;
    }

    /**
     * Authenticates a user with e-mail + password and returns a token bundle.
     *
     * <p>The method enforces:
     * <ul>
     *   <li>Account existence</li>
     *   <li>Account active status</li>
     *   <li>Account lock status</li>
     *   <li>Password correctness (with failed-attempt tracking)</li>
     * </ul>
     *
     * @param req validated login payload
     * @return token bundle for the authenticated user
     * @throws com.ivdr.exception.AuthenticationException on any authentication failure
     */
    /**
     * Returns all organizations a given email address belongs to.
     * Used by the frontend to populate the org-selector before login.
     */
    @Transactional(readOnly = true)
    public List<AuthDtos.OrganizationInfo> findOrganizationsByEmail(String email) {
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        return userRepository.findAllByEmail(normalizedEmail)
                .stream()
                .filter(u -> u.isActive() && u.getOrganization() != null)
                .map(u -> new AuthDtos.OrganizationInfo(
                        u.getOrganization().getId(),
                        u.getOrganization().getName(),
                        u.getOrganization().getSlug(),
                        u.getOrganization().getPlan()
                ))
                .distinct()
                .collect(Collectors.toList());
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        String normalizedEmail = req.email().toLowerCase(Locale.ROOT);

        // --- 1. Look up user — org-scoped if organizationId provided -----------
        User user;
        if (req.organizationId() != null) {
            user = userRepository.findByEmailAndOrganizationId(normalizedEmail, req.organizationId())
                    .orElseThrow(() -> ApiException.unauthorized("Invalid credentials"));
        } else {
            user = userRepository.findByEmail(normalizedEmail)
                    .orElseThrow(() -> ApiException.unauthorized("Invalid credentials"));
        }

        // --- 2. Active check -----------------------------------------------------
        if (!user.isActive()) {
            throw ApiException.unauthorized("Account is deactivated");
        }

        // --- 3. Lock check -------------------------------------------------------
        if (user.isLocked()) {
            log.warn("Login attempt on locked account: userId={}", user.getId());
            publishAuthEvent(AuditEventType.LOGIN_FAILED, user, java.util.Map.of("status", "account_already_locked"));
            throw ApiException.forbidden("Account is temporarily locked. Please try again later.");
        }

        // --- 4. Password check ---------------------------------------------------
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            handleFailedAttempt(user);
            throw ApiException.unauthorized("Invalid credentials");
        }

        // --- 5. Successful login — reset failure counter -------------------------
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(LocalDateTime.now());

        // --- 6. Persist refresh token hash ---------------------------------------
        TokenResponse response = buildTokenResponse(user);
        storeRefreshToken(user, response.refreshToken());

        userRepository.save(user);

        // Publish USER_LOGIN audit event
        publishAuthEvent(AuditEventType.USER_LOGIN, user, java.util.Map.of("email", user.getEmail()));

        log.info("User logged in: userId={}, email={}", user.getId(), user.getEmail());
        return response;
    }

    /**
     * Issues a new access token (and optionally a new refresh token) in exchange
     * for a valid, non-revoked refresh token.
     *
     * @param refreshToken the raw refresh token supplied by the client
     * @return a new token bundle
     * @throws com.ivdr.exception.AuthenticationException if the token is invalid or revoked
     */
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        // 1. Verify standard JWT validity
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw ApiException.unauthorized("Invalid or expired refresh token");
        }

        UUID userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        String tokenHash = cryptoUtil.sha256Hex(refreshToken.getBytes(StandardCharsets.UTF_8));

        // 2. Look up the hashed token in database
        RefreshToken tokenEntity = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> ApiException.unauthorized("Refresh token not found or revoked"));

        if (!tokenEntity.isValid() || !tokenEntity.getUser().getId().equals(userId)) {
            throw ApiException.unauthorized("Refresh token is invalid or revoked");
        }

        // Revoke the old refresh token
        tokenEntity.setRevoked(true);
        refreshTokenRepository.save(tokenEntity);

        // Look up user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("User not found for refresh token"));

        if (!user.isActive()) {
            throw ApiException.unauthorized("Account is deactivated");
        }

        // Rotate the refresh token: issue new tokens and save new hash.
        TokenResponse response = buildTokenResponse(user);
        storeRefreshToken(user, response.refreshToken());

        log.info("Refresh token rotated for userId={}", userId);
        return response;
    }

    /**
     * Revokes the supplied refresh token, effectively logging the user out of the
     * device/session that holds that token.
     *
     * @param refreshToken the raw refresh token to revoke
     */
    @Transactional
    public void logout(String refreshToken) {
        try {
            UUID userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
            UUID orgId = jwtTokenProvider.getOrganizationIdFromToken(refreshToken);

            String tokenHash = cryptoUtil.sha256Hex(refreshToken.getBytes(StandardCharsets.UTF_8));
            refreshTokenRepository.revokeByTokenHash(tokenHash);

            // Publish USER_LOGOUT audit event
            com.ivdr.domain.audit.event.AuditEvent event = com.ivdr.domain.audit.event.AuditEvent.of(
                    com.ivdr.domain.audit.event.AuditEventType.USER_LOGOUT,
                    orgId,
                    userId,
                    "user",
                    userId.toString(),
                    java.util.Map.of("action", "logout")
            );
            auditEventProducer.publishAuditEvent(event);

            log.info("Refresh token revoked");
        } catch (Exception ex) {
            // Treat invalid tokens as already-revoked — logout is idempotent.
            log.debug("Logout called with invalid/already-revoked token: {}", ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Increments the failed-login counter for the given user and, if the
     * threshold is reached, locks the account for {@value #LOCK_DURATION_MINUTES}
     * minutes.
     *
     * @param user the user who failed authentication
     */
    private void handleFailedAttempt(User user) {
        int attempts = user.getFailedLoginCount() + 1;
        user.setFailedLoginCount(attempts);

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            LocalDateTime lockUntil = LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES);
            user.setLockedUntil(lockUntil);
            log.warn("Account locked after {} failed attempts: userId={}, lockedUntil={}",
                    attempts, user.getId(), lockUntil);

            // Publish ACCOUNT_LOCKED audit event
            publishAuthEvent(AuditEventType.ACCOUNT_LOCKED, user, java.util.Map.of("failedAttempts", attempts, "lockDurationMinutes", LOCK_DURATION_MINUTES));
        } else {
            // Publish LOGIN_FAILED audit event
            publishAuthEvent(AuditEventType.LOGIN_FAILED, user, java.util.Map.of("failedAttempts", attempts));
        }

        userRepository.save(user);
    }

    /**
     * Builds a {@link TokenResponse} for the given user by asking
     * {@link JwtTokenProvider} to generate the access and refresh tokens.
     *
     * @param user the authenticated user
     * @return a fully populated token response
     */
    private TokenResponse buildTokenResponse(User user) {
        UserPrincipal principal = UserPrincipal.fromUser(user);
        String accessToken  = jwtTokenProvider.generateAccessToken(principal);
        String refreshToken = jwtTokenProvider.generateRefreshToken(principal);
        long   expiresIn    = jwtTokenProvider.getAccessTokenExpirySeconds();

        UserInfo userInfo = new UserInfo(
                user.getId(),
                user.getOrganization().getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole()
        );

        return new TokenResponse(accessToken, refreshToken, expiresIn, userInfo);
    }

    /**
     * Converts an arbitrary string into a URL-safe slug by:
     * <ol>
     *   <li>Normalising to ASCII (stripping accents).</li>
     *   <li>Lower-casing.</li>
     *   <li>Replacing runs of non-alphanumeric characters with a hyphen.</li>
     *   <li>Trimming leading/trailing hyphens.</li>
     * </ol>
     *
     * @param input the raw organisation name
     * @return a URL-safe slug
     */
    private static String slugify(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        return NON_ALPHANUMERIC
                .matcher(normalized.toLowerCase(Locale.ROOT))
                .replaceAll("-")
                .replaceAll("^-|-$", "");
    }

    private void storeRefreshToken(User user, String rawRefreshToken) {
        String tokenHash = cryptoUtil.sha256Hex(rawRefreshToken.getBytes(StandardCharsets.UTF_8));
        // refreshExpiryMs is in milliseconds, convert to LocalDateTime
        LocalDateTime expiresAt = LocalDateTime.now().plusNanos(refreshExpiryMs * 1_000_000L);

        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(tokenHash)
                .expiresAt(expiresAt)
                .revoked(false)
                .build();
        refreshTokenRepository.save(token);
    }
}
