package com.ivdr.domain.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JPA entity representing an application user in the IVDR platform.
 * Every user belongs to exactly one {@link Organization} (tenant).
 * Authentication state (failed logins, account lock) is managed here.
 */
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                // An email address must be unique within an organisation.
                @UniqueConstraint(
                        name = "uq_users_email_org",
                        columnNames = {"email", "organization_id"}
                )
        },
        indexes = {
                @Index(name = "idx_users_email", columnList = "email"),
                @Index(name = "idx_users_organization_id", columnList = "organization_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    /**
     * Surrogate primary key — generated as a random UUID by Hibernate.
     */
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    /**
     * The tenant organisation this user belongs to.
     * Lazy-loaded to avoid N+1 queries; always loaded explicitly where needed.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_users_organization"))
    private Organization organization;

    /**
     * The user's email address — used as the login credential.
     * Must be unique within the organisation.
     */
    @Column(name = "email", nullable = false, length = 320)
    private String email;

    /**
     * BCrypt hash of the user's password. Never stored or returned in plain text.
     */
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    /**
     * Full display name of the user (e.g. "Jane Doe").
     */
    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    /**
     * Application-level role — controls access to resources.
     * Typical values: "ADMIN", "MEMBER", "VIEWER".
     */
    @Column(name = "role", nullable = false, length = 50)
    @Builder.Default
    private String role = "MEMBER";

    /**
     * Whether the user account is enabled. Disabled accounts cannot log in
     * regardless of lock state.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    /**
     * Consecutive failed login attempts since last successful login.
     * Resets to zero on successful authentication.
     */
    @Column(name = "failed_login_count", nullable = false)
    @Builder.Default
    private int failedLoginCount = 0;

    /**
     * When set and in the future, the account is temporarily locked and
     * further login attempts will be rejected until this timestamp passes.
     */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    /**
     * Timestamp of the most recent successful login. May be {@code null} for
     * accounts that have never been used.
     */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /**
     * Audit timestamp — set at INSERT time.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Audit timestamp — updated at every UPDATE.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // -------------------------------------------------------------------------
    // Business logic helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when the account is currently locked due to repeated
     * failed login attempts.  A lock expires automatically once
     * {@code lockedUntil} falls in the past.
     *
     * @return {@code true} if the account is locked right now
     */
    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    /**
     * Initialises both audit timestamps immediately before the first INSERT.
     */
    @PrePersist
    protected void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Updates {@code updatedAt} on every subsequent UPDATE.
     */
    @PreUpdate
    protected void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
