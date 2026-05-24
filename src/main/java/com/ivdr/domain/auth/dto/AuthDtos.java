package com.ivdr.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Namespace class that groups all Auth-domain Data Transfer Objects.
 *
 * <p>Using inner {@code record} types keeps related DTOs co-located and avoids
 * cluttering the package with a large number of single-type files.  Each record
 * is effectively a public, immutable DTO with compiler-generated accessors,
 * {@code equals}, {@code hashCode}, and {@code toString}.</p>
 *
 * <p>Validation annotations on record components are processed by Bean Validation
 * when the record is used as a {@code @Valid} {@code @RequestBody} parameter.</p>
 */
public final class AuthDtos {

    /** Utility class — not instantiable. */
    private AuthDtos() {}

    // -------------------------------------------------------------------------
    // Inbound request DTOs
    // -------------------------------------------------------------------------

    /**
     * Payload for the {@code POST /auth/register} endpoint.
     *
     * <p>A successful registration creates a new {@link com.ivdr.domain.auth.entity.Organization}
     * and its first admin {@link com.ivdr.domain.auth.entity.User} in a single transaction.</p>
     *
     * @param organizationName human-readable name for the new tenant
     * @param fullName         display name of the registering user
     * @param email            login e-mail address
     * @param password         plain-text password (min 8 chars); hashed before storage
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
            String password
    ) {}

    /**
     * Payload for the {@code POST /auth/login} endpoint.
     *
     * @param email    the user's e-mail address
     * @param password the user's plain-text password
     */
    public record LoginRequest(
            @Email(message = "A valid e-mail address is required")
            @NotBlank(message = "Email is required")
            String email,

            @NotBlank(message = "Password is required")
            String password
    ) {}

    /**
     * Payload for the {@code POST /auth/refresh} endpoint.
     *
     * @param refreshToken the opaque refresh token previously issued by the server
     */
    public record RefreshRequest(
            @NotBlank(message = "Refresh token is required")
            String refreshToken
    ) {}

    // -------------------------------------------------------------------------
    // Outbound response DTOs
    // -------------------------------------------------------------------------

    /**
     * Token bundle returned on successful authentication (login, register, refresh).
     *
     * @param accessToken  short-lived JWT bearer token
     * @param refreshToken long-lived opaque token for obtaining new access tokens
     * @param expiresIn    access-token lifetime in seconds
     * @param user         lightweight profile of the authenticated user
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
}
