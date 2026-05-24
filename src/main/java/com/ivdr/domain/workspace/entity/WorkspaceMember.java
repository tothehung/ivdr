package com.ivdr.domain.workspace.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JPA entity representing a user's membership in a {@link Workspace}.
 *
 * <p>Each row associates one user with one workspace and grants that user a
 * specific {@link MemberRole}.  A user may hold only one role per workspace —
 * uniqueness is enforced by the {@code uq_workspace_members} constraint.
 *
 * <p>The {@code role} is stored as a {@code VARCHAR} using
 * {@link EnumType#STRING} so that database values remain human-readable and
 * schema migrations are not required when new roles are added.
 *
 * <p>{@code joinedAt} is set automatically by the {@link #prePersist()} callback.
 */
@Entity
@Table(
        name = "workspace_members",
        uniqueConstraints = {
                // One user can hold only one role per workspace.
                @UniqueConstraint(
                        name  = "uq_workspace_members_workspace_user",
                        columnNames = {"workspace_id", "user_id"}
                )
        },
        indexes = {
                @Index(name = "idx_workspace_members_workspace_id", columnList = "workspace_id"),
                @Index(name = "idx_workspace_members_user_id",      columnList = "user_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceMember {

    // -------------------------------------------------------------------------
    // Nested enum — workspace roles
    // -------------------------------------------------------------------------

    /**
     * Enumeration of roles a user may hold within a workspace.
     *
     * <ul>
     *   <li>{@link #OWNER}  — full control; can manage members and delete the workspace.</li>
     *   <li>{@link #EDITOR} — can create, edit, and delete documents.</li>
     *   <li>{@link #VIEWER} — read-only access to workspace content.</li>
     * </ul>
     */
    public enum MemberRole {
        /** Full administrative control over the workspace. */
        OWNER,
        /** Can create and modify content but cannot manage workspace settings. */
        EDITOR,
        /** Read-only access; cannot create or modify any content. */
        VIEWER
    }

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
    // Foreign key references (stored as plain UUIDs for performance)
    // -------------------------------------------------------------------------

    /**
     * The workspace this membership record belongs to.
     */
    @Column(name = "workspace_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID workspaceId;

    /**
     * The user who is a member of the workspace.
     */
    @Column(name = "user_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID userId;

    // -------------------------------------------------------------------------
    // Role
    // -------------------------------------------------------------------------

    /**
     * The role granted to the user in this workspace.
     * Stored as a string so the column value is human-readable in the database.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MemberRole role;

    // -------------------------------------------------------------------------
    // Audit timestamp
    // -------------------------------------------------------------------------

    /**
     * The instant at which the user joined (or was added to) the workspace.
     * Set once at INSERT time and never modified.
     */
    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;

    // -------------------------------------------------------------------------
    // JPA lifecycle callback
    // -------------------------------------------------------------------------

    /**
     * Sets {@code joinedAt} to the current time immediately before the first INSERT.
     */
    @PrePersist
    protected void prePersist() {
        this.joinedAt = LocalDateTime.now();
    }
}
