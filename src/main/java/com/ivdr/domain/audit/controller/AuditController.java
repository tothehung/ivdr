package com.ivdr.domain.audit.controller;

import com.ivdr.domain.audit.entity.AuditLog;
import com.ivdr.domain.audit.event.AuditEventType;
import com.ivdr.domain.audit.repository.AuditLogRepository;
import com.ivdr.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller that exposes read-only endpoints for querying the immutable
 * audit log.
 *
 * <p>All endpoints operate within the scope of the <em>authenticated user's own
 * organisation</em>: the {@code organizationId} is always resolved from the JWT
 * principal and never accepted as a user-supplied path or query parameter. This
 * prevents cross-tenant data leakage.
 *
 * <h3>Authorisation</h3>
 * Every endpoint requires the caller to hold the {@code ROLE_ADMIN} or
 * {@code ROLE_MANAGER} role (enforced via {@link PreAuthorize}).
 *
 * <h3>Pagination</h3>
 * All list endpoints return a {@link Page} and accept standard Spring
 * {@code Pageable} query parameters ({@code page}, {@code size}, {@code sort}).
 * Default page size is 20, ordered by {@code createdAt} descending.
 *
 * <h3>Base path</h3>
 * {@code /audit}
 */
@Slf4j
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final AuditLogRepository auditLogRepository;

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    /**
     * Returns a paginated list of all audit logs for the authenticated user's
     * organisation, ordered by most-recent first.
     *
     * <p><strong>GET</strong> {@code /audit/logs}
     *
     * <p>Example query parameters:
     * <pre>{@code ?page=0&size=20&sort=createdAt,desc}</pre>
     *
     * @param principal The currently authenticated user (JWT-derived).
     * @param pageable  Pagination / sort parameters from query string.
     * @return A page of {@link AuditLog} projections for the organisation.
     */
    @GetMapping("/logs")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Page<AuditLog>> getOrganisationLogs(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        UUID orgId = principal.organizationId();

        log.debug("[AuditController] GET /logs orgId={}, page={}, size={}",
                orgId, pageable.getPageNumber(), pageable.getPageSize());

        Page<AuditLog> page =
                auditLogRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId, pageable);

        return ResponseEntity.ok(page);
    }

    /**
     * Returns a paginated list of audit logs for a specific user within the
     * authenticated caller's organisation.
     *
     * <p><strong>GET</strong> {@code /audit/logs/user/{userId}}
     *
     * <p>Managers can use this endpoint to investigate a particular employee's
     * activity without accessing logs from other organisations.
     *
     * @param userId    The target user's UUID (path variable).
     * @param principal The currently authenticated caller.
     * @param pageable  Pagination / sort parameters.
     * @return A page of {@link AuditLog} rows filtered by user.
     */
    @GetMapping("/logs/user/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Page<AuditLog>> getUserLogs(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        UUID orgId = principal.organizationId();

        log.debug("[AuditController] GET /logs/user/{} orgId={}", userId, orgId);

        Page<AuditLog> page =
                auditLogRepository.findByOrganizationIdAndUserId(orgId, userId, pageable);

        return ResponseEntity.ok(page);
    }

    /**
     * Returns a paginated list of audit logs filtered by event type within the
     * authenticated caller's organisation.
     *
     * <p><strong>GET</strong> {@code /audit/logs/type/{eventType}}
     *
     * <p>The {@code eventType} path variable is matched case-insensitively against
     * the values of {@link AuditEventType}. An unknown event type returns an empty page.
     *
     * @param eventType The event type string (e.g. {@code DOCUMENT_DOWNLOADED}).
     * @param principal The currently authenticated caller.
     * @param pageable  Pagination / sort parameters.
     * @return A page of {@link AuditLog} rows filtered by event type.
     */
    @GetMapping("/logs/type/{eventType}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Page<AuditLog>> getLogsByEventType(
            @PathVariable String eventType,
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        UUID orgId = principal.organizationId();

        log.debug("[AuditController] GET /logs/type/{} orgId={}", eventType, orgId);

        // Validate the event type against the known enum values; return empty
        // page for unknown values rather than throwing a 400.
        String normalizedType = eventType.toUpperCase();
        boolean validType;
        try {
            AuditEventType.valueOf(normalizedType);
            validType = true;
        } catch (IllegalArgumentException e) {
            log.warn("[AuditController] Unknown eventType requested: {}", eventType);
            validType = false;
        }

        if (!validType) {
            return ResponseEntity.ok(Page.empty(pageable));
        }

        Page<AuditLog> page =
                auditLogRepository.findByOrganizationIdAndEventType(
                        orgId, normalizedType, pageable);

        return ResponseEntity.ok(page);
    }

    /**
     * Returns a paginated list of audit logs that were flagged as anomalous
     * within the authenticated caller's organisation.
     *
     * <p><strong>GET</strong> {@code /audit/anomalies}
     *
     * <p>Results are ordered by most-recent first (descending {@code createdAt}).
     * This endpoint is intended for a security-monitoring dashboard.
     *
     * @param principal The currently authenticated caller.
     * @param pageable  Pagination / sort parameters.
     * @return A page of {@link AuditLog} rows where {@code isAnomaly=true}.
     */
    @GetMapping("/anomalies")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<Page<AuditLog>> getAnomalies(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        UUID orgId = principal.organizationId();

        log.debug("[AuditController] GET /anomalies orgId={}", orgId);

        Page<AuditLog> page =
                auditLogRepository.findByOrganizationIdAndIsAnomalyOrderByCreatedAtDesc(
                        orgId, true, pageable);

        return ResponseEntity.ok(page);
    }
}
