package com.ivdr.domain.workspace.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JPA entity representing a collaborative workspace in the IVDR platform.
 *
 * <p>A workspace is scoped to a single {@code organization} (tenant) and groups
 * together documents, members, and permissions.  Privacy is controlled by the
 * {@code isPrivate} flag: private workspaces are only visible to their members,
 * while public workspaces are discoverable by every member of the organisation.
 *
 * <p>Audit timestamps ({@code createdAt}, {@code updatedAt}) are managed
 * automatically by the JPA lifecycle callbacks {@link #prePersist()} and
 * {@link #preUpdate()}.
 */
@Entity
@Table(
        name = "workspaces",
        indexes = {
                @Index(name = "idx_workspaces_organization_id",  columnList = "organization_id"),
                @Index(name = "idx_workspaces_created_by",       columnList = "created_by")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {

    // -------------------------------------------------------------------------
    // Primary key
    // -------------------------------------------------------------------------

    /**
     * Surrogate primary key — randomly generated UUID, never updated after INSERT.
     */
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    // -------------------------------------------------------------------------
    // Tenant / ownership
    // -------------------------------------------------------------------------

    /**
     * The organisation (tenant) this workspace belongs to.
     * Used as a multi-tenancy discriminator on every query.
     */
    @Column(name = "organization_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID organizationId;

    /**
     * The user who originally created this workspace.
     * Stored as a plain FK UUID — the creator is always the first OWNER member.
     */
    @Column(name = "created_by", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID createdBy;

    // -------------------------------------------------------------------------
    // Descriptive fields
    // -------------------------------------------------------------------------

    /**
     * Human-readable workspace name; must not be blank.
     * Uniqueness within an organisation is enforced at the service layer.
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Optional free-text description of the workspace purpose.
     * Stored as a large text column to accommodate lengthy descriptions.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // -------------------------------------------------------------------------
    // Visibility
    // -------------------------------------------------------------------------

    /**
     * When {@code true} the workspace is private and only visible to members.
     * When {@code false} (default) the workspace can be discovered by any
     * member of the same organisation.
     */
    @Column(name = "is_private", nullable = false)
    @Builder.Default
    private boolean isPrivate = false;

    // -------------------------------------------------------------------------
    // Audit timestamps
    // -------------------------------------------------------------------------

    /**
     * Timestamp set once at INSERT time; never modified afterwards.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp updated on every subsequent UPDATE via {@link #preUpdate()}.
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // -------------------------------------------------------------------------
    // JPA lifecycle callbacks
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
     * Refreshes {@code updatedAt} on every subsequent UPDATE operation.
     */
    @PreUpdate
    protected void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
