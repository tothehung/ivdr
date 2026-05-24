package com.ivdr.domain.document.repository;

import com.ivdr.domain.document.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for the {@link Document} entity.
 *
 * <p>All query methods are workspace-scoped so that data from one workspace
 * can never be accidentally surfaced to users of another — this is the
 * repository-layer complement to the database-level Row-Level Security (RLS)
 * policies.</p>
 *
 * <p>Soft-delete is the only supported deletion mechanism; hard deletes
 * are performed exclusively via database maintenance jobs, never from
 * application code.</p>
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    // -------------------------------------------------------------------------
    // Paginated listing
    // -------------------------------------------------------------------------

    /**
     * Returns a paginated slice of documents belonging to a specific workspace
     * that currently have the given {@code status}.
     *
     * <p>Typical usages:
     * <ul>
     *   <li>{@code status = "ACTIVE"} — list all live documents for a workspace.</li>
     *   <li>{@code status = "ARCHIVED"} — list archived documents for audit review.</li>
     * </ul>
     *
     * @param workspaceId the UUID of the workspace to filter by
     * @param status      the document lifecycle status string to match
     * @param pageable    pagination and sorting parameters
     * @return a {@link Page} of matching {@link Document} records
     */
    Page<Document> findByWorkspaceIdAndStatus(
            UUID workspaceId,
            String status,
            Pageable pageable
    );

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    /**
     * Full list (un-paginated) search for documents within a workspace matching
     * a given status and with a name containing {@code name} (case-insensitive).
     *
     * <p>Intended for lightweight typeahead / autocomplete use-cases where the
     * result set is expected to be small. For larger datasets consider using
     * the paginated variant or delegating to Elasticsearch.</p>
     *
     * @param workspaceId the UUID of the workspace to filter by
     * @param status      the document lifecycle status string to match
     * @param name        the substring to search for within the document name
     * @return a list of matching {@link Document} records, potentially empty
     */
    List<Document> findByWorkspaceIdAndStatusAndNameContainingIgnoreCase(
            UUID workspaceId,
            String status,
            String name
    );

    // -------------------------------------------------------------------------
    // Counting
    // -------------------------------------------------------------------------

    /**
     * Returns the count of documents in a workspace that have the given status.
     *
     * <p>Used primarily for metrics dashboards and upload-quota enforcement.</p>
     *
     * @param workspaceId the UUID of the workspace to count within
     * @param status      the document lifecycle status to count
     * @return number of documents matching the criteria
     */
    long countByWorkspaceIdAndStatus(UUID workspaceId, String status);

    // -------------------------------------------------------------------------
    // Soft-delete
    // -------------------------------------------------------------------------

    /**
     * Soft-deletes a document by setting its {@code status} column to
     * {@code 'DELETED'} without physically removing the row.
     *
     * <p>This preserves the audit trail and allows recovery if required.
     * The corresponding S3 object is removed by a separate scheduled
     * cleanup job.</p>
     *
     * <p>The method is annotated with {@link Modifying} to signal to Spring
     * Data that this is a write operation; the transaction boundary is managed
     * by the calling service layer.</p>
     *
     * @param documentId the UUID of the document to soft-delete
     * @param workspaceId the UUID of the owning workspace (prevents cross-workspace deletion)
     * @return the number of rows updated (1 if found and updated, 0 if not found)
     */
    @Modifying
    @Query("""
            UPDATE Document d
               SET d.status = 'DELETED',
                   d.updatedAt = CURRENT_TIMESTAMP
             WHERE d.id = :documentId
               AND d.workspaceId = :workspaceId
               AND d.status <> 'DELETED'
            """)
    int softDelete(@Param("documentId") UUID documentId,
                   @Param("workspaceId") UUID workspaceId);

    // -------------------------------------------------------------------------
    // Lookup helpers
    // -------------------------------------------------------------------------

    /**
     * Finds a non-deleted document by its primary key, scoped to the given workspace.
     *
     * <p>Combining workspace-scoping with the status check in one query prevents
     * information-leakage between workspaces even if the caller supplies an id
     * that belongs to a different workspace.</p>
     *
     * @param id          the document UUID
     * @param workspaceId the workspace the caller is authorised to access
     * @return an {@link Optional} containing the document if found and active
     */
    @Query("""
            SELECT d FROM Document d
             WHERE d.id = :id
               AND d.workspaceId = :workspaceId
               AND d.status <> 'DELETED'
            """)
    Optional<Document> findActiveByIdAndWorkspaceId(@Param("id") UUID id,
                                                     @Param("workspaceId") UUID workspaceId);
}
