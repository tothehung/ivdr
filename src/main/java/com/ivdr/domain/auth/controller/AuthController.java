package com.ivdr.domain.auth.controller;

import com.ivdr.domain.auth.dto.AuthDtos;
import com.ivdr.domain.auth.dto.AuthDtos.*;
import com.ivdr.domain.auth.service.AuthService;
import com.ivdr.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller exposing the Auth domain endpoints.
 *
 * <p>All endpoints are public (no authentication required) because they are
 * responsible for <em>issuing</em> credentials.  They should be excluded from
 * any global JWT filter via the security configuration.</p>
 *
 * <h2>Endpoints</h2>
 * <table>
 *   <tr><th>Method</th><th>Path</th><th>Status</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/auth/register</td><td>201</td><td>Register a new org + admin user</td></tr>
 *   <tr><td>POST</td><td>/auth/login</td><td>200</td><td>Authenticate and receive tokens</td></tr>
 *   <tr><td>POST</td><td>/auth/refresh</td><td>200</td><td>Exchange refresh token for new tokens</td></tr>
 *   <tr><td>POST</td><td>/auth/logout</td><td>204</td><td>Revoke refresh token</td></tr>
 * </table>
 *
 * <h2>Response envelope</h2>
 * Successful responses are wrapped in an {@link ApiResponse} envelope
 * ({@code { "success": true, "data": {...} }}).
 * Error responses follow the global exception-handler format.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    // -------------------------------------------------------------------------
    // POST /auth/register
    // -------------------------------------------------------------------------

    /**
     * Creates a new tenant organisation together with its first admin user and
     * returns an initial token bundle so the client can immediately begin
     * authenticated work.
     *
     * @param request validated registration payload
     * @return {@code 201 Created} with a {@link TokenResponse} wrapped in {@link ApiResponse}
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<TokenResponse>> register(
            @Valid @RequestBody RegisterRequest request) {

        log.info("Registration request for organisation: '{}'", request.organizationName());

        TokenResponse tokenResponse = authService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(tokenResponse));
    }

    // -------------------------------------------------------------------------
    // POST /auth/login
    // -------------------------------------------------------------------------

    /**
     * Authenticates a user with e-mail + password credentials.
     *
     * <p>Returns a token bundle on success.  Returns {@code 401 Unauthorized}
     * on invalid credentials or a locked/inactive account (handled by the
     * global exception handler).</p>
     *
     * @param request validated login payload
     * @return {@code 200 OK} with a {@link TokenResponse} wrapped in {@link ApiResponse}
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest request) {

        log.info("Login request for email: '{}'", request.email());

        TokenResponse tokenResponse = authService.login(request);

        return ResponseEntity
                .ok(ApiResponse.ok(tokenResponse));
    }

    // -------------------------------------------------------------------------
    // POST /auth/refresh
    // -------------------------------------------------------------------------

    /**
     * Issues a new access token (and rotated refresh token) in exchange for a
     * valid refresh token.
     *
     * <p>The old refresh token is revoked as part of this operation (token
     * rotation).  Clients must store the newly issued refresh token for future
     * use.</p>
     *
     * @param request contains the refresh token to validate and rotate
     * @return {@code 200 OK} with a new {@link TokenResponse} wrapped in {@link ApiResponse}
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @Valid @RequestBody RefreshRequest request) {

        log.debug("Token refresh request received");

        TokenResponse tokenResponse = authService.refresh(request.refreshToken());

        return ResponseEntity
                .ok(ApiResponse.ok(tokenResponse));
    }

    // -------------------------------------------------------------------------
    // POST /auth/logout
    // -------------------------------------------------------------------------

    /**
     * Revokes the supplied refresh token, effectively ending the user session on
     * the device that holds that token.
     *
     * <p>This call is idempotent: passing an already-revoked or unknown token
     * returns {@code 204} without an error, to avoid leaking information about
     * token validity.</p>
     *
     * @param request contains the refresh token to revoke
     * @return {@code 204 No Content}
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody RefreshRequest request) {

        log.debug("Logout request received");

        authService.logout(request.refreshToken());

        return ResponseEntity.noContent().build();
    }
}
