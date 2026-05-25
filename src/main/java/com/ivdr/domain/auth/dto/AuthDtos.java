package com.ivdr.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Namespace class that groups all Auth-domain Data Transfer Objects.
 */
public final class AuthDtos {

    /** Utility class — not instantiable. */
    private AuthDtos() {}

    // -------------------------------------------------------------------------
    // Inbound request DTOs
    // -------------------------------------------------------------------------

    /**
     * Payload for the {@code POST /auth/register} endpoint.
     */
    public record RegisterRequest(
            @NotBlank(message = "Organization name is required")
            String organizationName,

            @NotBlank(message = "Full name is required")
            String fullName,

            @Email(message = "A valid e-mail address is required")
            @NotBlank(message = "Email is required")
            String email,

            @Size(min = 8, message = "Password must be at least 8 characters")
            @NotBlank(message = "Password is required")
            String password,

            String phone,
            String jobTitle
    ) {}

    /**
     * Payload for the {@code POST /auth/login} endpoint.
     * organizationId is optional — if provided, login is scoped to that org.
     */
    public record LoginRequest(
            @Email(message = "A valid e-mail address is required")
            @NotBlank(message = "Email is required")
            String email,

            @NotBlank(message = "Password is required")
            String password,

            UUID organizationId
    ) {}

    /**
     * Payload for the {@code POST /auth/refresh} endpoint.
     */
    public record RefreshRequest(
            @NotBlank(message = "Refresh token is required")
            String refreshToken
    ) {}

    /**
     * Request to send an OTP email before completing registration.
     */
    public record SendOtpRequest(
            @Email(message = "A valid e-mail address is required")
            @NotBlank(message = "Email is required")
            String email,

            @NotBlank(message = "Full name is required")
            String fullName,

            @NotBlank(message = "Organization name is required")
            String organizationName
    ) {}

    /**
     * Payload to verify OTP code sent by email.
     */
    public record OtpVerifyRequest(
            @Email(message = "A valid e-mail address is required")
            @NotBlank(message = "Email is required")
            String email,

            @NotBlank(message = "OTP code is required")
            @Size(min = 6, max = 6, message = "OTP must be exactly 6 digits")
            String otp
    ) {}

    // -------------------------------------------------------------------------
    // Outbound response DTOs
    // -------------------------------------------------------------------------

    /**
     * Token bundle returned on successful authentication (login, register, refresh).
     */
    public record TokenResponse(
            String accessToken,
            String refreshToken,
            long expiresIn,
            UserInfo user
    ) {}

    /**
     * Compact user projection embedded inside {@link TokenResponse}.
     * Contains only the data the client typically needs to bootstrap a session.
     *
     * @param userId         the user's UUID
     * @param organizationId the tenant organisation UUID
     * @param email          the user's e-mail address
     * @param fullName       the user's display name
     * @param role           the user's application role (e.g. "ADMIN", "MEMBER")
     */
    public record UserInfo(
            UUID userId,
            UUID organizationId,
            String email,
            String fullName,
            String role
    ) {}

    /**
     * Response payload sent when an OTP email is generated/sent.
     */
    public record OtpSentResponse(
            String message,
            String email,
            boolean success
    ) {}

    /**
     * Simple projection of organization info.
     */
    public record OrganizationInfo(
            UUID id,
            String name,
            String slug,
            String plan
    ) {}
}
