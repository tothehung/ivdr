package com.ivdr.domain.workspace.service;

import com.ivdr.common.exception.ApiException;
import com.ivdr.domain.audit.event.AuditEvent;
import com.ivdr.domain.audit.event.AuditEventType;
import com.ivdr.domain.audit.producer.AuditEventProducer;
import com.ivdr.domain.auth.entity.User;
import com.ivdr.domain.auth.repository.UserRepository;
import com.ivdr.domain.workspace.dto.WorkspaceDtos.AddMemberRequest;
import com.ivdr.domain.workspace.dto.WorkspaceDtos.CreateWorkspaceRequest;
import com.ivdr.domain.workspace.dto.WorkspaceDtos.MemberResponse;
import com.ivdr.domain.workspace.dto.WorkspaceDtos.WorkspaceResponse;
import com.ivdr.domain.workspace.entity.Workspace;
import com.ivdr.domain.workspace.entity.WorkspaceMember;
import com.ivdr.domain.workspace.entity.WorkspaceMember.MemberRole;
import com.ivdr.domain.workspace.repository.WorkspaceMemberRepository;
import com.ivdr.domain.workspace.repository.WorkspaceRepository;
import com.ivdr.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic service for the Workspace domain.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Create and delete workspaces, enforcing that the creator becomes the initial OWNER.</li>
 *   <li>List workspaces accessible to the authenticated user (membership-based).</li>
 *   <li>Manage workspace membership: add and remove members with role validation.</li>
 *   <li>Publish structured {@link AuditEvent}s to the Kafka audit pipeline on every state-changing operation.</li>
 * </ul>
 *
 * <p>All public methods are {@link Transactional}: reads use a read-only hint for
 * performance, while writes participate in a full read-write transaction.
 *
 * <p>Access control is enforced via the private
 * {@link #validateMembership(UUID, UUID, MemberRole...)} helper, which throws
 * {@link ApiException#forbidden(String)} when the caller does not hold one of
 * the required roles.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    private final WorkspaceRepository       workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final UserRepository            userRepository;
    private final AuditEventProducer        auditEventProducer;



    // =========================================================================
    // Public service methods
    // =========================================================================

    /**
     * Creates a new workspace and automatically adds the creator as an {@link MemberRole#OWNER}.
     *
     * <p>After the workspace and its initial membership record are persisted within the same
     * transaction, a {@link AuditEventType#WORKSPACE_CREATED} event is published to Kafka.
     *
     * @param req       the creation request carrying name, description, and privacy flag
     * @param principal the authenticated user who will become the workspace owner
     * @return the newly created workspace as a {@link WorkspaceResponse}
     */
    @Transactional
    public WorkspaceResponse createWorkspace(CreateWorkspaceRequest req, UserPrincipal principal) {

        // 1. Build and persist the workspace.
        Workspace workspace = Workspace.builder()
                .organizationId(principal.organizationId())
                .createdBy(principal.userId())
                .name(req.name())
                .description(req.description())
                .isPrivate(req.isPrivate())
                .build();

        workspace = workspaceRepository.save(workspace);
        log.info("Workspace '{}' created by user={}", workspace.getName(), principal.userId());

        // 2. Add the creator as OWNER member.
        WorkspaceMember ownerMembership = WorkspaceMember.builder()
                .workspaceId(workspace.getId())
                .userId(principal.userId())
                .role(MemberRole.OWNER)
                .build();

        memberRepository.save(ownerMembership);

        // 3. Fire audit event (non-blocking; Kafka send is async).
        publishAuditEvent(
                AuditEventType.WORKSPACE_CREATED,
                principal.organizationId(),
                principal.userId(),
                workspace.getId(),
                Map.of("workspaceName", workspace.getName(), "isPrivate", workspace.isPrivate())
        );

        return toWorkspaceResponse(workspace, 1);
    }

    /**
     * Returns a paginated list of workspaces of which the authenticated user is a member.
     *
     * <p>The membership table is the authoritative source for which workspaces are
     * accessible — this avoids exposing private workspaces to non-members.
     *
     * @param principal the authenticated user
     * @param pageable  pagination and sorting parameters
     * @return a {@link Page} of {@link WorkspaceResponse} objects visible to the user
     */
    @Transactional(readOnly = true)
    public Page<WorkspaceResponse> listWorkspaces(UserPrincipal principal, Pageable pageable) {

        // Collect all workspace IDs the user is a member of.
        List<UUID> memberWorkspaceIds = memberRepository
                .findAllByUserId(principal.userId())
                .stream()
                .map(WorkspaceMember::getWorkspaceId)
                .toList();

        if (memberWorkspaceIds.isEmpty()) {
            return Page.empty(pageable);
        }

        // Fetch only the workspace entities the user is a member of (efficient IN query).
        List<Workspace> all = workspaceRepository.findAllByIdIn(memberWorkspaceIds);

        // Apply manual pagination (suitable for moderate dataset sizes).
        int start = (int) pageable.getOffset();
        int end   = Math.min(start + pageable.getPageSize(), all.size());
        List<WorkspaceResponse> pageContent = (start > all.size())
                ? List.of()
                : all.subList(start, end)
                     .stream()
                     .map(ws -> toWorkspaceResponse(ws, memberRepository.countByWorkspaceId(ws.getId())))
                     .toList();

        return new PageImpl<>(pageContent, pageable, all.size());
    }

    /**
     * Returns a single workspace by its ID, validating that the caller is a member.
     *
     * @param workspaceId the UUID of the workspace to retrieve
     * @param principal   the authenticated user
     * @return the workspace as a {@link WorkspaceResponse}
     * @throws ApiException 404 if the workspace does not exist
     * @throws ApiException 403 if the caller is not a member of the workspace
     */
    @Transactional(readOnly = true)
    public WorkspaceResponse getWorkspace(UUID workspaceId, UserPrincipal principal) {

        Workspace workspace = findWorkspaceOrThrow(workspaceId);
        validateMembership(workspaceId, principal.userId()); // any role is acceptable

        int memberCount = (int) memberRepository.countByWorkspaceId(workspaceId);
        return toWorkspaceResponse(workspace, memberCount);
    }

    /**
     * Retrieves all members of a specific workspace.
     *
     * <p>The caller must be a member of the workspace.
     *
     * @param workspaceId the workspace UUID
     * @param principal   the authenticated user
     * @return a list of {@link MemberResponse} objects
     */
    @Transactional(readOnly = true)
    public List<MemberResponse> getMembers(UUID workspaceId, UserPrincipal principal) {
        validateMembership(workspaceId, principal.userId());
        findWorkspaceOrThrow(workspaceId);

        List<WorkspaceMember> members = memberRepository.findAllByWorkspaceId(workspaceId);
        
        List<UUID> userIds = members.stream().map(WorkspaceMember::getUserId).toList();
        List<User> users = userRepository.findAllById(userIds);
        Map<UUID, User> userMap = users.stream().collect(java.util.stream.Collectors.toMap(User::getId, u -> u));

        return members.stream().map(m -> {
            User u = userMap.get(m.getUserId());
            return new MemberResponse(
                    u.getId(),
                    u.getEmail(),
                    u.getFullName(),
                    m.getRole().name(),
                    m.getJoinedAt()
            );
        }).toList();
    }

    /**
     * Adds a user to a workspace with the specified role.
     *
     * <p>Only a workspace {@link MemberRole#OWNER} may add members.  The target user
     * must exist and must not already be a member of the workspace.
     *
     * @param workspaceId the workspace to add the member to
     * @param req         request containing the target {@code userId} and {@code role}
     * @param principal   the authenticated caller (must be OWNER)
     * @return a {@link MemberResponse} describing the newly added member
     * @throws ApiException 403 if the caller is not an OWNER
     * @throws ApiException 404 if the workspace or target user does not exist
     * @throws ApiException 409 if the user is already a member
     * @throws ApiException 400 if the supplied role string is not a valid {@link MemberRole}
     */
    @Transactional
    public MemberResponse addMember(UUID workspaceId, AddMemberRequest req, UserPrincipal principal) {

        // Only owners may add new members.
        validateMembership(workspaceId, principal.userId(), MemberRole.OWNER);

        // The workspace must exist.
        findWorkspaceOrThrow(workspaceId);

        // Parse and validate the role string early to give a meaningful error.
        MemberRole memberRole = parseMemberRole(req.role());

        // Validate that the target user exists.
        User targetUser = userRepository.findById(req.userId())
                .orElseThrow(() -> ApiException.notFound("User not found: " + req.userId()));

        // Guard against duplicate membership.
        if (memberRepository.existsByWorkspaceIdAndUserId(workspaceId, req.userId())) {
            throw ApiException.conflict("User is already a member of this workspace.");
        }

        WorkspaceMember newMember = WorkspaceMember.builder()
                .workspaceId(workspaceId)
                .userId(req.userId())
                .role(memberRole)
                .build();

        newMember = memberRepository.save(newMember);
        log.info("User {} added to workspace {} with role {}", req.userId(), workspaceId, memberRole);

        publishAuditEvent(
                AuditEventType.WORKSPACE_MEMBER_ADDED,
                principal.organizationId(),
                principal.userId(),
                workspaceId,
                Map.of(
                        "addedUserId", req.userId().toString(),
                        "role",        memberRole.name()
                )
        );

        return new MemberResponse(
                targetUser.getId(),
                targetUser.getEmail(),
                targetUser.getFullName(),
                newMember.getRole().name(),
                newMember.getJoinedAt()
        );
    }

    /**
     * Removes a user from a workspace.
     *
     * <p>The caller must be a workspace {@link MemberRole#OWNER}.
     * An OWNER may not remove themselves if they are the only OWNER left —
     * the workspace would become unmanageable.
     *
     * @param workspaceId the workspace to remove the member from
     * @param userId      the UUID of the user to remove
     * @param principal   the authenticated caller (must be OWNER)
     * @throws ApiException 403 if the caller is not an OWNER
     * @throws ApiException 404 if the workspace or the target membership does not exist
     * @throws ApiException 409 if removal would leave the workspace with no OWNER
     */
    @Transactional
    public void removeMember(UUID workspaceId, UUID userId, UserPrincipal principal) {

        validateMembership(workspaceId, principal.userId(), MemberRole.OWNER);
        findWorkspaceOrThrow(workspaceId);

        // Ensure the target membership actually exists.
        if (!memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)) {
            throw ApiException.notFound("User is not a member of this workspace.");
        }

        // Guard: prevent removal that would leave no OWNER.
        boolean isSelfRemoval = principal.userId().equals(userId);
        if (isSelfRemoval) {
            long ownerCount = memberRepository.findAllByUserId(userId).stream()
                    .filter(m -> m.getWorkspaceId().equals(workspaceId) && m.getRole() == MemberRole.OWNER)
                    .count();

            long totalOwners = memberRepository.countByWorkspaceIdAndRole(workspaceId, MemberRole.OWNER);
            if (totalOwners <= 1) {
                throw ApiException.conflict(
                        "Cannot remove the last OWNER. Assign another OWNER before leaving.");
            }
        }

        memberRepository.deleteByWorkspaceIdAndUserId(workspaceId, userId);
        log.info("User {} removed from workspace {} by {}", userId, workspaceId, principal.userId());

        publishAuditEvent(
                AuditEventType.WORKSPACE_MEMBER_REMOVED,
                principal.organizationId(),
                principal.userId(),
                workspaceId,
                Map.of("removedUserId", userId.toString())
        );
    }

    /**
     * Permanently deletes a workspace and all of its membership records.
     *
     * <p>Only the workspace {@link MemberRole#OWNER} may perform this action.
     * Cascade deletion of membership records is done explicitly via the
     * repository to avoid relying on database-level cascades.
     *
     * @param workspaceId the UUID of the workspace to delete
     * @param principal   the authenticated caller (must be OWNER)
     * @throws ApiException 403 if the caller is not an OWNER
     * @throws ApiException 404 if the workspace does not exist
     */
    @Transactional
    public void deleteWorkspace(UUID workspaceId, UserPrincipal principal) {

        validateMembership(workspaceId, principal.userId(), MemberRole.OWNER);
        Workspace workspace = findWorkspaceOrThrow(workspaceId);

        // Cascade-delete all membership records first (safer than DB cascade).
        List<WorkspaceMember> members = memberRepository.findAllByWorkspaceId(workspaceId);
        memberRepository.deleteAll(members);

        workspaceRepository.delete(workspace);
        log.info("Workspace {} deleted by user={}", workspaceId, principal.userId());

        publishAuditEvent(
                AuditEventType.WORKSPACE_DELETED,
                principal.organizationId(),
                principal.userId(),
                workspaceId,
                Map.of("workspaceName", workspace.getName())
        );
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Validates that the given user is a member of the workspace and — when
     * {@code allowedRoles} is non-empty — holds at least one of the permitted roles.
     *
     * <p>Throws {@link ApiException#forbidden(String)} (403) when:
     * <ul>
     *   <li>the user is not a member of the workspace, or</li>
     *   <li>the user's role is not in {@code allowedRoles} (if roles are specified).</li>
     * </ul>
     *
     * @param workspaceId  the workspace to check membership in
     * @param userId       the user whose membership is validated
     * @param allowedRoles zero or more roles that are permitted; if empty, any role is accepted
     * @throws ApiException 403 if access is denied
     */
    private void validateMembership(UUID workspaceId, UUID userId, MemberRole... allowedRoles) {

        WorkspaceMember membership = memberRepository
                .findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> ApiException.forbidden(
                        "You are not a member of workspace: " + workspaceId));

        if (allowedRoles.length > 0) {
            boolean hasRequiredRole = Arrays.asList(allowedRoles).contains(membership.getRole());
            if (!hasRequiredRole) {
                throw ApiException.forbidden(
                        "This action requires one of the following roles: "
                        + Arrays.toString(allowedRoles));
            }
        }
    }

    /**
     * Loads a workspace by its ID or throws a 404 {@link ApiException}.
     *
     * @param workspaceId the workspace's UUID
     * @return the persistent {@link Workspace} entity
     * @throws ApiException 404 if not found
     */
    private Workspace findWorkspaceOrThrow(UUID workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> ApiException.notFound("Workspace not found: " + workspaceId));
    }

    /**
     * Parses a role string into a {@link MemberRole} enum constant.
     *
     * @param roleString the role name, case-insensitive
     * @return the matching {@link MemberRole}
     * @throws ApiException 400 if the string does not match any known role
     */
    private MemberRole parseMemberRole(String roleString) {
        try {
            return MemberRole.valueOf(roleString.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(
                    "Invalid role '" + roleString + "'. Valid roles are: OWNER, EDITOR, VIEWER");
        }
    }

    /**
     * Converts a {@link Workspace} entity and a pre-computed member count into a
     * {@link WorkspaceResponse} record.
     *
     * @param workspace   the entity to map
     * @param memberCount the current number of members
     * @return a new {@link WorkspaceResponse}
     */
    private WorkspaceResponse toWorkspaceResponse(Workspace workspace, long memberCount) {
        return new WorkspaceResponse(
                workspace.getId(),
                workspace.getName(),
                workspace.getDescription(),
                workspace.isPrivate(),
                workspace.getCreatedBy(),
                workspace.getCreatedAt(),
                (int) memberCount
        );
    }

    /**
     * Publishes a structured {@link AuditEvent} to the Kafka audit pipeline.
     *
     * <p>The send is fire-and-forget at the application level; Kafka producer
     * retries and idempotence are configured in {@code KafkaConfig}.  Failures
     * are logged but do not roll back the surrounding transaction — audit
     * logging must never block business operations.
     *
     * @param type        the type of event
     * @param orgId       the organisation (tenant) UUID
     * @param actorId     the user who triggered the action
     * @param workspaceId the affected workspace's UUID
     * @param metadata    additional contextual data
     */
    private void publishAuditEvent(
            AuditEventType type,
            UUID orgId,
            UUID actorId,
            UUID workspaceId,
            Map<String, Object> metadata) {

        try {
            AuditEvent event = AuditEvent.of(
                    type,
                    orgId,
                    actorId,
                    "workspace",
                    workspaceId.toString(),
                    metadata
            );
            auditEventProducer.publishAuditEvent(event);
            log.debug("Audit event published via producer: type={}, workspace={}", type, workspaceId);
        } catch (Exception ex) {
            log.error("Failed to publish audit event type={} for workspace={}: {}",
                    type, workspaceId, ex.getMessage(), ex);
        }
    }
}
