package com.ivdr.domain.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Analytics service that aggregates usage data from the IVDR audit_logs table.
 *
 * <p>All methods query the {@code audit_logs} table using named-parameter JDBC
 * so they remain decoupled from JPA and can be optimised with raw SQL.
 * Expensive aggregation queries are protected by a Spring Cache layer
 * ({@code @Cacheable("analytics")}) — configure a TTL via your caching
 * provider (e.g. Redis) to control staleness.
 *
 * <h2>Cache strategy</h2>
 * <ul>
 *   <li>Cache name: {@code analytics}</li>
 *   <li>Cache key is derived from all method parameters (Spring default)</li>
 *   <li>Recommended TTL: 5–15 minutes depending on traffic volume</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    // -------------------------------------------------------------------------
    // Dependencies — NamedParameterJdbcTemplate for flexible SQL aggregation
    // -------------------------------------------------------------------------

    /**
     * Spring JDBC template with named-parameter support.
     * Injected from the auto-configured DataSource.
     */
    private final NamedParameterJdbcTemplate jdbcTemplate;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns a heatmap of document view counts for a workspace over a date range.
     *
     * <p>The result maps each {@code document_id} (as a string) to the number of
     * {@code DOCUMENT_VIEWED} audit events recorded between {@code from} and {@code to}
     * (both inclusive).  Documents with zero views in the period are omitted.
     *
     * <p>Result is cached; a cache eviction is expected after significant ingestion
     * windows (e.g. nightly batch or explicit eviction via admin endpoint).
     *
     * @param workspaceId UUID of the workspace to aggregate for
     * @param from        start date (inclusive)
     * @param to          end date (inclusive)
     * @return {@link LinkedHashMap} ordered by view count descending:
     *         {@code documentId → viewCount}
     */
    @Cacheable(value = "analytics", key = "'heatmap:' + #workspaceId + ':' + #from + ':' + #to")
    public Map<String, Long> getDocumentHeatmap(UUID workspaceId, LocalDate from, LocalDate to) {
        log.info("Computing document heatmap for workspace {} from {} to {}", workspaceId, from, to);

        String sql = """
                SELECT resource_id::text  AS document_id,
                       COUNT(*)           AS view_count
                  FROM audit_logs
                 WHERE metadata->>'workspaceId' = :workspaceIdStr
                   AND event_type    = 'DOCUMENT_VIEWED'
                   AND created_at  >= :from
                   AND created_at  <= :to::date + INTERVAL '1 day' - INTERVAL '1 second'
                 GROUP BY resource_id
                 ORDER BY view_count DESC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceIdStr", workspaceId.toString())
                .addValue("from",           from.atStartOfDay())
                .addValue("to",             to);

        Map<String, Long> heatmap = new LinkedHashMap<>();
        jdbcTemplate.query(sql, params, rs -> {
            heatmap.put(rs.getString("document_id"), rs.getLong("view_count"));
        });

        log.debug("Heatmap for workspace {} returned {} entries", workspaceId, heatmap.size());
        return heatmap;
    }

    /**
     * Returns a day-by-day activity timeline for a specific user.
     *
     * <p>Each point in the returned list represents one calendar day within
     * {@code [from, to]} that had at least one audit event.  Days with zero
     * activity are excluded.  The list is ordered chronologically.
     *
     * @param userId UUID of the user whose activity is being analysed
     * @param from   start date (inclusive)
     * @param to     end date (inclusive)
     * @return chronologically ordered list of {@link ActivityPoint} records
     */
    @Cacheable(value = "analytics", key = "'timeline:' + #userId + ':' + #from + ':' + #to")
    public List<ActivityPoint> getUserActivityTimeline(UUID userId, LocalDate from, LocalDate to) {
        log.info("Computing activity timeline for user {} from {} to {}", userId, from, to);

        String sql = """
                SELECT DATE(created_at) AS activity_date,
                       COUNT(*)          AS event_count
                  FROM audit_logs
                 WHERE user_id     = :userId
                   AND created_at >= :from
                   AND created_at <= :to::date + INTERVAL '1 day' - INTERVAL '1 second'
                 GROUP BY DATE(created_at)
                 ORDER BY activity_date ASC
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("from",   from.atStartOfDay())
                .addValue("to",     to);

        List<ActivityPoint> timeline = jdbcTemplate.query(sql, params, (rs, rowNum) ->
                new ActivityPoint(
                        rs.getObject("activity_date", LocalDate.class),
                        rs.getLong("event_count")
                )
        );

        log.debug("Timeline for user {} returned {} data points", userId, timeline.size());
        return timeline;
    }

    /**
     * Returns aggregate statistics for a workspace.
     *
     * <p>This is the most expensive query — it joins across multiple tables —
     * so it is always cached.  The result includes:
     * <ul>
     *   <li>{@code totalDocs}      — total number of documents stored in the workspace</li>
     *   <li>{@code totalDownloads} — cumulative DOCUMENT_DOWNLOADED audit events</li>
     *   <li>{@code activeUsers}    — users with at least one event in the last 30 days</li>
     *   <li>{@code storageBytes}   — sum of all document file sizes in bytes</li>
     * </ul>
     *
     * @param workspaceId workspace to aggregate
     * @return a {@link WorkspaceStats} record with the four aggregate values
     */
    @Cacheable(value = "analytics", key = "'workspace-stats:' + #workspaceId")
    public WorkspaceStats getWorkspaceStats(UUID workspaceId) {
        log.info("Computing workspace stats for workspace {}", workspaceId);

        // Total documents and total storage
        String docSql = """
                SELECT COUNT(*)        AS total_docs,
                       COALESCE(SUM(file_size_bytes), 0) AS storage_bytes
                  FROM documents
                 WHERE workspace_id = :workspaceId
                   AND status != 'DELETED'
                """;

        // Total downloads from audit_logs
        String downloadSql = """
                SELECT COUNT(*) AS total_downloads
                  FROM audit_logs
                 WHERE metadata->>'workspaceId' = :workspaceIdStr
                   AND event_type   = 'DOCUMENT_DOWNLOADED'
                """;

        // Active users (at least one event in the last 30 days)
        String activeUsersSql = """
                SELECT COUNT(DISTINCT user_id) AS active_users
                  FROM audit_logs
                 WHERE (metadata->>'workspaceId' = :workspaceIdStr OR (resource_type = 'workspace' AND resource_id = :workspaceIdStr))
                   AND created_at >= NOW() - INTERVAL '30 days'
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceId",    workspaceId)
                .addValue("workspaceIdStr", workspaceId.toString());

        long totalDocs      = 0L;
        long storageBytes   = 0L;
        long totalDownloads = 0L;
        long activeUsers    = 0L;

        // Query 1: documents table
        Map<String, Object> docRow = jdbcTemplate.queryForMap(docSql, params);
        if (docRow != null) {
            totalDocs    = toLong(docRow.get("total_docs"));
            storageBytes = toLong(docRow.get("storage_bytes"));
        }

        // Query 2: download count
        Map<String, Object> dlRow = jdbcTemplate.queryForMap(downloadSql, params);
        if (dlRow != null) {
            totalDownloads = toLong(dlRow.get("total_downloads"));
        }

        // Query 3: active users
        Map<String, Object> auRow = jdbcTemplate.queryForMap(activeUsersSql, params);
        if (auRow != null) {
            activeUsers = toLong(auRow.get("active_users"));
        }

        WorkspaceStats stats = new WorkspaceStats(totalDocs, totalDownloads, activeUsers, storageBytes);
        log.debug("Workspace stats for {}: {}", workspaceId, stats);
        return stats;
    }

    // =========================================================================
    // Inner records (value types)
    // =========================================================================

    /**
     * A single point on a user's daily activity timeline.
     *
     * @param date  the calendar day
     * @param count number of audit events recorded on that day
     */
    public record ActivityPoint(LocalDate date, long count) implements java.io.Serializable {}

    /**
     * Aggregate statistics for a workspace.
     *
     * @param totalDocs      total number of non-deleted documents
     * @param totalDownloads cumulative download events
     * @param activeUsers    distinct users with any event in the last 30 days
     * @param storageBytes   sum of all document file sizes in bytes
     */
    public record WorkspaceStats(
            long totalDocs,
            long totalDownloads,
            long activeUsers,
            long storageBytes
    ) implements java.io.Serializable {}

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Safely converts a database numeric result to a primitive {@code long}. */
    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number num) return num.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
