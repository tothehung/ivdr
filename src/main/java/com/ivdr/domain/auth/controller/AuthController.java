package com.ivdr.domain.auth.controller;

import com.ivdr.domain.auth.dto.AuthDtos;
import com.ivdr.domain.auth.dto.AuthDtos.*;
import com.ivdr.domain.auth.service.AuthService;
import com.ivdr.domain.auth.service.EmailService;
import com.ivdr.domain.auth.service.OtpService;
import com.ivdr.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller exposing the Auth domain endpoints.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>POST /auth/register — Register (after OTP verified)</li>
 *   <li>POST /auth/send-otp — Send registration OTP email</li>
 *   <li>POST /auth/verify-otp — Verify OTP code</li>
 *   <li>POST /auth/login — Authenticate and receive tokens</li>
 *   <li>GET  /auth/orgs?email={email} — List organizations for an email</li>
 *   <li>POST /auth/refresh — Exchange refresh token for new tokens</li>
 *   <li>POST /auth/logout — Revoke refresh token</li>
 * </ul>
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final OtpService  otpService;
    private final EmailService emailService;

    // -------------------------------------------------------------------------
    // POST /auth/send-otp — Step 1 of registration: send OTP
    // -------------------------------------------------------------------------

    /**
     * Sends a 6-digit OTP to the provided email address as the first step of registration.
     */
    @PostMapping("/send-otp")
    public ResponseEntity<ApiResponse<OtpSentResponse>> sendOtp(
            @Valid @RequestBody SendOtpRequest request) {

        log.info("OTP send request for email: '{}'", request.email());

        String otp = otpService.generateAndStore(request.email());
        emailService.sendOtpEmail(request.email(), otp, request.fullName(), request.organizationName());

        return ResponseEntity.ok(ApiResponse.ok(
                new OtpSentResponse(
                        "OTP sent to " + request.email() + ". Check your inbox.",
                        request.email(),
                        true
                )
        ));
    }

    // -------------------------------------------------------------------------
    // POST /auth/verify-otp — Step 2 of registration: verify OTP
    // -------------------------------------------------------------------------

    /**
     * Verifies the OTP code before allowing registration to proceed.
     */
    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<OtpSentResponse>> verifyOtp(
            @Valid @RequestBody OtpVerifyRequest request) {

        log.info("OTP verification request for email: '{}'", request.email());

        boolean valid = otpService.verify(request.email(), request.otp());
        if (!valid) {
            return ResponseEntity
                    .status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ApiResponse.error("Invalid or expired OTP. Please request a new one."));
        }

        return ResponseEntity.ok(ApiResponse.ok(
                new OtpSentResponse("OTP verified successfully.", request.email(), true)
        ));
    }

    // -------------------------------------------------------------------------
    // POST /auth/register — Step 3 of registration: create account
    // -------------------------------------------------------------------------

    /**
     * Creates a new tenant organisation together with its first admin user.
     * OTP must have been verified before calling this endpoint.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<TokenResponse>> register(
            @Valid @RequestBody RegisterRequest request) {

        log.info("Registration request for organisation: '{}'", request.organizationName());

        TokenResponse tokenResponse = authService.register(request);

        // Send welcome email asynchronously
        emailService.sendWelcomeEmail(request.email(), request.fullName(), request.organizationName());

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(tokenResponse));
    }

    // -------------------------------------------------------------------------
    // GET /auth/orgs — List organizations for a given email (multi-org login)
    // -------------------------------------------------------------------------

    /**
     * Returns the list of organizations the given email address belongs to.
     * Used to populate an org-selector dropdown before login.
     */
    @GetMapping("/orgs")
    public ResponseEntity<ApiResponse<List<OrganizationInfo>>> getOrganizationsForEmail(
            @RequestParam String email) {

        log.debug("Org lookup for email: '{}'", email);

        List<OrganizationInfo> orgs = authService.findOrganizationsByEmail(email);
        return ResponseEntity.ok(ApiResponse.ok(orgs));
    }

    // -------------------------------------------------------------------------
    // POST /auth/login — Authenticate
    // -------------------------------------------------------------------------

    /**
     * Authenticates a user with e-mail + password credentials.
     * If organizationId is included in the request, login is scoped to that org.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest request) {

        log.info("Login request for email: '{}', org: '{}'",
                request.email(), request.organizationId() != null ? request.organizationId() : "any");

        TokenResponse tokenResponse = authService.login(request);

        return ResponseEntity.ok(ApiResponse.ok(tokenResponse));
    }

    // -------------------------------------------------------------------------
    // POST /auth/refresh
    // -------------------------------------------------------------------------

    /**
     * Issues a new access token (and rotated refresh token) in exchange for a valid refresh token.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @Valid @RequestBody RefreshRequest request) {

        log.debug("Token refresh request received");

        TokenResponse tokenResponse = authService.refresh(request.refreshToken());

        return ResponseEntity.ok(ApiResponse.ok(tokenResponse));
    }

    // -------------------------------------------------------------------------
    // POST /auth/logout
    // -------------------------------------------------------------------------

    /**
     * Revokes the supplied refresh token, effectively ending the user session.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody RefreshRequest request) {

        log.debug("Logout request received");

        authService.logout(request.refreshToken());

        return ResponseEntity.noContent().build();
    }
}
