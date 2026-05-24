package com.ivdr.domain.analytics.controller;

import com.ivdr.common.response.ApiResponse;
import com.ivdr.domain.analytics.service.AnalyticsService;
import com.ivdr.domain.analytics.service.AnalyticsService.ActivityPoint;
import com.ivdr.domain.analytics.service.AnalyticsService.WorkspaceStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller that exposes analytics endpoints for the IVDR platform.
 *
 * <p>All endpoints require an authenticated session with at least the
 * {@code MEMBER} role.  Workspace-level endpoints additionally require
 * {@code MANAGER} or {@code ADMIN} access to avoid leaking sensitive
 * aggregate data to general members.
 *
 * <p>Base path: {@code /analytics}
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /analytics/workspace/{workspaceId}/heatmap?from=&to=}</li>
 *   <li>{@code GET /analytics/users/{userId}/timeline?from=&to=}</li>
 *   <li>{@code GET /analytics/workspace/{workspaceId}/stats}</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final AnalyticsService analyticsService;

    // =========================================================================
    // GET /analytics/workspace/{workspaceId}/heatmap
    // =========================================================================

    /**
     * Returns a document view heatmap for the given workspace and date range.
     *
     * <p>The response {@code data} field is a map of {@code documentId → viewCount},
     * ordered by view count descending so the hottest documents appear first.
     *
     * <p>Date parameters use ISO-8601 format ({@code yyyy-MM-dd}).
     * If omitted, {@code from} defaults to 30 days ago and {@code to} defaults to today.
     *
     * @param workspaceId UUID of the workspace
     * @param from        start date (inclusive, format {@code yyyy-MM-dd})
     * @param to          end date (inclusive, format {@code yyyy-MM-dd})
     * @return 200 OK with {@code Map<String, Long>} heatmap data
     */
    @GetMapping("/workspace/{workspaceId}/heatmap")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getDocumentHeatmap(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();

        validateDateRange(effectiveFrom, effectiveTo);

        log.info("GET heatmap workspace={} from={} to={}", workspaceId, effectiveFrom, effectiveTo);

        Map<String, Long> heatmap = analyticsService.getDocumentHeatmap(
                workspaceId, effectiveFrom, effectiveTo);

        return ResponseEntity.ok(
                ApiResponse.ok("Document heatmap retrieved", heatmap));
    }

    // =========================================================================
    // GET /analytics/users/{userId}/timeline
    // =========================================================================

    /**
     * Returns a day-by-day activity timeline for the specified user.
     *
     * <p>Any authenticated user can query their own timeline.  Admins and managers
     * may query any user's timeline (enforced by the service layer in a real
     * implementation; here the role guard is kept permissive at the controller
     * level to support self-service dashboards).
     *
     * @param userId UUID of the user whose timeline is requested
     * @param from   start date (inclusive, format {@code yyyy-MM-dd})
     * @param to     end date (inclusive, format {@code yyyy-MM-dd})
     * @return 200 OK with a chronologically ordered list of {@link ActivityPoint}
     */
    @GetMapping("/users/{userId}/timeline")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public ResponseEntity<ApiResponse<List<ActivityPoint>>> getUserActivityTimeline(
            @PathVariable UUID userId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate effectiveFrom = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();

        validateDateRange(effectiveFrom, effectiveTo);

        log.info("GET timeline user={} from={} to={}", userId, effectiveFrom, effectiveTo);

        List<ActivityPoint> timeline = analyticsService.getUserActivityTimeline(
                userId, effectiveFrom, effectiveTo);

        return ResponseEntity.ok(
                ApiResponse.ok("User activity timeline retrieved", timeline));
    }

    // =========================================================================
    // GET /analytics/workspace/{workspaceId}/stats
    // =========================================================================

    /**
     * Returns aggregate statistics for a workspace.
     *
     * <p>Only {@code ADMIN} and {@code MANAGER} roles are permitted, as the
     * statistics include storage consumption and user activity counts that
     * should not be visible to general members.
     *
     * @param workspaceId UUID of the workspace
     * @return 200 OK with a {@link WorkspaceStats} record
     */
    @GetMapping("/workspace/{workspaceId}/stats")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<WorkspaceStats>> getWorkspaceStats(
            @PathVariable UUID workspaceId) {

        log.info("GET stats workspace={}", workspaceId);

        WorkspaceStats stats = analyticsService.getWorkspaceStats(workspaceId);
        return ResponseEntity.ok(
                ApiResponse.ok("Workspace statistics retrieved", stats));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Validates that {@code from} is not after {@code to}.
     *
     * @param from start date
     * @param to   end date
     * @throws IllegalArgumentException if the range is invalid
     */
    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "Invalid date range: 'from' (%s) must not be after 'to' (%s)".formatted(from, to));
        }
        // Guard against absurdly large ranges that would stress the database
        if (from.isBefore(to.minusYears(2))) {
            throw new IllegalArgumentException(
                    "Date range too large: maximum allowed range is 2 years");
        }
    }
}
