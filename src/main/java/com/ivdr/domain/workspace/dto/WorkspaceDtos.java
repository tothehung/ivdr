package com.ivdr.domain.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Data Transfer Object (DTO) container for the Workspace domain.
 *
 * <p>All request and response shapes are modelled as Java 21 {@code record}s
 * nested inside this class.  Records are immutable by design — they have no
 * setters and their fields are finalised at construction time, which makes them
 * ideal for request/response payloads that should never be mutated after being
 * deserialised.
 *
 * <p>Validation annotations ({@code @NotBlank}, {@code @NotNull}) are applied
 * on request records so that Spring's {@code @Valid} in the controller layer
 * catches malformed input before it reaches the service layer.
 */
public class WorkspaceDtos {

    /** Private constructor — this is a purely static container class. */
    private WorkspaceDtos() {}

    // =========================================================================
    // Request records
    // =========================================================================

    /**
     * Request body for the {@code POST /workspaces} endpoint.
     *
     * @param name        Human-readable workspace name; must not be blank.
     * @param description Optional free-text description of the workspace.
     * @param isPrivate   When {@code true} the workspace is hidden from
     *                    organisation-wide discovery; only members can see it.
     */
    public record CreateWorkspaceRequest(
            @NotBlank(message = "Workspace name must not be blank")
            String name,

            String description,

            boolean isPrivate
    ) {}

    /**
     * Request body for the {@code POST /workspaces/{id}/members} endpoint.
     *
     * @param userId The UUID of the user to add to the workspace.
     * @param role   The role to assign; must match a {@code WorkspaceMember.MemberRole}
     *               constant: {@code OWNER}, {@code EDITOR}, or {@code VIEWER}.
     */
    public record AddMemberRequest(
            @NotNull(message = "userId must not be null")
            UUID userId,

            @NotBlank(message = "Role must not be blank")
            String role
    ) {}

    // =========================================================================
    // Response records
    // =========================================================================

    /**
     * Response body returned by workspace read and write endpoints.
     *
     * @param id          UUID of the workspace.
     * @param name        Human-readable workspace name.
     * @param description Optional description.
     * @param isPrivate   Whether the workspace is private.
     * @param createdBy   UUID of the user who created the workspace.
     * @param createdAt   Timestamp when the workspace was created.
     * @param memberCount Current number of members in the workspace.
     */
    public record WorkspaceResponse(
            UUID id,
            String name,
            String description,
            boolean isPrivate,
            UUID createdBy,
            LocalDateTime createdAt,
            int memberCount,
            String role
    ) {}

    /**
     * Response body for individual workspace member entries.
     *
     * @param userId    UUID of the member.
     * @param email     Member's e-mail address.
     * @param fullName  Member's display name.
     * @param role      Role name ({@code "OWNER"}, {@code "EDITOR"}, or {@code "VIEWER"}).
     * @param joinedAt  Timestamp when the user joined the workspace.
     */
    public record MemberResponse(
            UUID userId,
            String email,
            String fullName,
            String role,
            LocalDateTime joinedAt
    ) {}
}
