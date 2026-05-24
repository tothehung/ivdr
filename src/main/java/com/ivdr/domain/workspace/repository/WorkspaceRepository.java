package com.ivdr.domain.workspace.repository;

import com.ivdr.domain.workspace.entity.Workspace;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Workspace} entities.
 *
 * <p>Provides standard CRUD operations inherited from {@link JpaRepository} plus
 * custom query methods derived from Spring Data's method-naming conventions.
 *
 * <p>All query methods automatically scope results to the provided
 * {@code organizationId}, supporting the platform's multi-tenancy model.
 */
@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    /**
     * Returns all workspaces that belong to the given organisation.
     *
     * <p>This is an unfiltered, un-paginated list intended for administrative
     * operations (e.g. bulk exports).  Prefer
     * {@link #findByOrganizationIdAndIsPrivateFalse(UUID, Pageable)} for
     * user-facing listing endpoints to avoid returning private workspaces.
     *
     * @param organizationId the tenant organisation's UUID
     * @return all workspaces for the given organisation; never {@code null}
     */
    List<Workspace> findAllByOrganizationId(UUID organizationId);

    /**
     * Returns a paginated view of publicly discoverable workspaces within an
     * organisation, i.e. those with {@code isPrivate = false}.
     *
     * <p>Private workspaces are intentionally excluded — they must be accessed
     * only through the membership-aware service layer.
     *
     * @param organizationId the tenant organisation's UUID
     * @param pageable       pagination and sorting parameters
     * @return a {@link Page} of public workspaces; never {@code null}
     */
    Page<Workspace> findByOrganizationIdAndIsPrivateFalse(UUID organizationId, Pageable pageable);

    /**
     * Returns all workspaces whose UUIDs are in the given collection.
     *
     * <p>Used by {@code WorkspaceService#listWorkspaces} to bulk-fetch the
     * workspace entities for all IDs collected from the membership table,
     * avoiding a full-table scan of the {@code workspaces} table.
     *
     * @param ids the collection of workspace UUIDs to fetch
     * @return matching workspaces in any order; never {@code null}
     */
    List<Workspace> findAllByIdIn(java.util.Collection<UUID> ids);
}
