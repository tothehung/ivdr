package com.ivdr.domain.workspace.repository;

import com.ivdr.domain.workspace.entity.WorkspaceMember;
import com.ivdr.domain.workspace.entity.WorkspaceMember.MemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link WorkspaceMember} entities.
 *
 * <p>Provides standard CRUD operations inherited from {@link JpaRepository}
 * plus custom finders and a transactional bulk-delete method used by the
 * workspace service to manage membership lifecycle.
 */
@Repository
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    /**
     * Finds a single membership record identified by both workspace and user.
     *
     * <p>Returns an empty {@link Optional} when the user is not a member of
     * the specified workspace.  Used by the service layer to check roles and
     * validate access before performing sensitive operations.
     *
     * @param workspaceId the workspace's UUID
     * @param userId      the user's UUID
     * @return the membership record, or {@link Optional#empty()} if none exists
     */
    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    /**
     * Returns all workspace memberships held by a specific user.
     *
     * <p>Used to build the list of workspaces that are accessible to the
     * currently authenticated user (regardless of role).
     *
     * @param userId the user's UUID
     * @return all memberships for the given user; never {@code null}
     */
    List<WorkspaceMember> findAllByUserId(UUID userId);

    /**
     * Returns {@code true} if the user is already a member of the workspace.
     *
     * <p>This is a lightweight existence check used before adding a new member
     * to prevent duplicate membership records (which the unique constraint would
     * reject anyway, but an explicit check gives a better error message).
     *
     * @param workspaceId the workspace's UUID
     * @param userId      the user's UUID
     * @return {@code true} if a membership record exists; {@code false} otherwise
     */
    boolean existsByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    /**
     * Deletes the membership record for a specific user in a specific workspace.
     *
     * <p>This method is annotated {@link Transactional} because Spring Data
     * requires a transaction context to execute DML operations derived from
     * method names (as opposed to inherited {@code deleteById} / {@code delete}).
     *
     * @param workspaceId the workspace's UUID
     * @param userId      the user's UUID
     */
    @Transactional
    void deleteByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);

    /**
     * Returns all membership records for a given workspace.
     *
     * <p>Used by {@code WorkspaceService} to cascade-delete all members before
     * removing the workspace itself.
     *
     * @param workspaceId the workspace's UUID
     * @return all {@link WorkspaceMember}s in the workspace; never {@code null}
     */
    List<WorkspaceMember> findAllByWorkspaceId(UUID workspaceId);

    /**
     * Counts the total number of members in a workspace.
     *
     * <p>Used to populate the {@code memberCount} field in workspace response DTOs
     * without loading the full membership list.
     *
     * @param workspaceId the workspace's UUID
     * @return number of member records
     */
    long countByWorkspaceId(UUID workspaceId);

    /**
     * Counts the members in a workspace who hold a specific role.
     *
     * <p>Used to enforce the "at least one OWNER" invariant before removing a member.
     *
     * @param workspaceId the workspace's UUID
     * @param role        the role to count
     * @return number of members with the given role
     */
    long countByWorkspaceIdAndRole(UUID workspaceId, MemberRole role);
}
