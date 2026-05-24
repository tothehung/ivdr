package com.ivdr.domain.auth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JPA entity representing a tenant organisation in the IVDR platform.
 * Each organisation is a top-level tenant; all other domain data is scoped
 * to an organisation via row-level security in the database.
 */
@Entity
@Table(
        name = "organizations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_organizations_slug", columnNames = "slug")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    /**
     * Surrogate primary key — generated as a random UUID by Hibernate.
     * Stored as uuid type in PostgreSQL.
     */
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    /**
     * Human-readable display name of the organisation (e.g. "Acme Corp").
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * URL-safe unique identifier derived from the organisation name.
     * Used in sub-domain / path-based multi-tenancy routing.
     */
    @Column(name = "slug", nullable = false, unique = true, length = 100)
    private String slug;

    /**
     * Subscription plan identifier (e.g. "free", "professional", "enterprise").
     */
    @Column(name = "plan", nullable = false, length = 50)
    @Builder.Default
    private String plan = "free";

    /**
     * Whether the organisation (and therefore all its users) is enabled.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    /**
     * Timestamp of record creation — populated automatically in {@link #prePersist()}.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp of last modification — updated automatically in {@link #preUpdate()}.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    /**
     * Sets {@code createdAt} and {@code updatedAt} before the first INSERT.
     */
    @PrePersist
    protected void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Refreshes {@code updatedAt} on every subsequent UPDATE.
     */
    @PreUpdate
    protected void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
