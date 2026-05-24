package com.ivdr.domain.audit.repository;

import com.ivdr.domain.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link AuditLog} entities.
 *
 * <p>All query methods follow the Spring Data derived-query naming convention.
 * Complex aggregations or JPQL queries should be added via {@code @Query} annotations
 * in a future iteration.
 *
 * <h3>Idempotency</h3>
 * {@link #existsByEventId(UUID)} is used by the Kafka consumer to detect and skip
 * duplicate event deliveries before persisting a new row.
 *
 * <h3>Anomaly queries</h3>
 * {@link #findByUserIdAndEventTypeAndCreatedAtAfter} and
 * {@link #countByUserIdAndEventTypeAndCreatedAtAfter} are consumed by
 * {@link com.ivdr.domain.audit.service.AnomalyDetectionService} to evaluate
 * threshold-based rules within a sliding time window.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if an audit log row with the given {@code eventId}
     * already exists in the database.
     *
     * <p>Used by the Kafka consumer to implement exactly-once semantics:
     * if an event has already been persisted (e.g. due to a redelivery after
     * a consumer crash), the duplicate is silently discarded.
     *
     * @param eventId The unique event identifier carried in the Kafka message.
     * @return {@code true} if the event has already been processed.
     */
    boolean existsByEventId(UUID eventId);

    // -------------------------------------------------------------------------
    // Organisation-scoped queries
    // -------------------------------------------------------------------------

    /**
     * Returns a paginated view of all audit logs belonging to a given organisation,
     * ordered by descending creation time (most recent first).
     *
     * @param orgId    Tenant / organisation identifier.
     * @param pageable Pagination and sort parameters.
     * @return A page of {@link AuditLog} rows.
     */
    Page<AuditLog> findByOrganizationIdOrderByCreatedAtDesc(UUID orgId, Pageable pageable);

    /**
     * Returns a paginated view of audit logs for a specific user within an
     * organisation.
     *
     * <p>The result is ordered by the repository's default sort (creation time
     * ascending). Callers should pass an explicit {@link Pageable} sort if a
     * different order is required.
     *
     * @param orgId    Tenant / organisation identifier.
     * @param userId   The actor whose events are requested.
     * @param pageable Pagination and sort parameters.
     * @return A page of {@link AuditLog} rows.
     */
    Page<AuditLog> findByOrganizationIdAndUserId(UUID orgId, UUID userId, Pageable pageable);

    /**
     * Returns a paginated view of audit logs of a specific {@code eventType}
     * within an organisation.
     *
     * @param orgId      Tenant / organisation identifier.
     * @param eventType  String representation of
     *                   {@link com.ivdr.domain.audit.event.AuditEventType}.
     * @param pageable   Pagination and sort parameters.
     * @return A page of {@link AuditLog} rows.
     */
    Page<AuditLog> findByOrganizationIdAndEventType(UUID orgId, String eventType, Pageable pageable);

    /**
     * Returns a paginated view of audit logs that were flagged as anomalous within
     * an organisation, ordered by descending creation time.
     *
     * @param orgId      Tenant / organisation identifier.
     * @param isAnomaly  {@code true} to retrieve anomalous logs.
     * @param pageable   Pagination and sort parameters.
     * @return A page of {@link AuditLog} rows.
     */
    Page<AuditLog> findByOrganizationIdAndIsAnomalyOrderByCreatedAtDesc(
            UUID orgId, boolean isAnomaly, Pageable pageable);

    // -------------------------------------------------------------------------
    // Anomaly-detection sliding-window queries
    // -------------------------------------------------------------------------

    /**
     * Returns all audit logs for the given user and event type created after
     * the specified timestamp.
     *
     * <p>Used by {@link com.ivdr.domain.audit.service.AnomalyDetectionService}
     * to inspect the actual events within the detection window (e.g. to count
     * distinct documents viewed).
     *
     * @param userId    The actor to inspect.
     * @param eventType The event type filter (e.g. {@code "DOCUMENT_DOWNLOADED"}).
     * @param after     Lower bound (exclusive) of the time window.
     * @return An unordered list of matching {@link AuditLog} rows.
     */
    List<AuditLog> findByUserIdAndEventTypeAndCreatedAtAfter(
            UUID userId, String eventType, LocalDateTime after);

    /**
     * Counts audit logs for a specific user and event type created after the
     * specified timestamp.
     *
     * <p>More efficient than loading full rows when only the count is needed
     * (e.g. to check whether a download or login-failure threshold is exceeded).
     *
     * @param userId    The actor to inspect.
     * @param eventType The event type filter.
     * @param after     Lower bound (exclusive) of the time window.
     * @return The number of matching events.
     */
    long countByUserIdAndEventTypeAndCreatedAtAfter(
            UUID userId, String eventType, LocalDateTime after);
}
