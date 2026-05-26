package com.ivdr.domain.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Manages the PostgreSQL row-level security (RLS) tenant context for the current
 * database connection / thread.
 *
 * <h2>How tenant isolation works</h2>
 * <ol>
 *   <li>Before executing any tenant-scoped query the application calls
 *       {@link #setTenant(UUID)}, which invokes the database function
 *       {@code set_tenant_context(?::uuid)} on the current connection.</li>
 *   <li>PostgreSQL RLS policies read {@code current_setting('app.tenant_id')} and
 *       automatically filter every table access to the active tenant.</li>
 *   <li>After the request completes (e.g. in a servlet filter's finally-block)
 *       {@link #clearTenant()} should be called to reset the context, preventing
 *       context bleed to the next request that reuses the same connection from the
 *       pool.</li>
 * </ol>
 *
 * <h2>Thread-local tracking</h2>
 * A secondary {@link ThreadLocal} stores the current organisation UUID at the
 * <em>application</em> level, so that application code (services, auditing, etc.)
 * can read it via {@link #getCurrentOrgId()} without making a round-trip to the DB.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantContextService {

    // -------------------------------------------------------------------------
    // SQL constants
    // -------------------------------------------------------------------------

    /**
     * PostgreSQL function that writes both the tenant UUID and user UUID
     * into session-level settings.
     */
    private static final String SET_SESSION_CONTEXT_SQL = "SELECT set_session_context(?::uuid, ?::uuid)";

    // -------------------------------------------------------------------------
    // Thread-local storage — application-level tenant and user tracking
    // -------------------------------------------------------------------------

    /**
     * Holds the organisation UUID for the current request thread.
     */
    private static final ThreadLocal<UUID> CURRENT_ORG_ID = new ThreadLocal<>();

    /**
     * Holds the user UUID for the current request thread.
     */
    private static final ThreadLocal<UUID> CURRENT_USER_ID = new ThreadLocal<>();

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Activates the tenant context for {@code organizationId} and {@code userId}
     * on both the database connection and the application-level thread-local.
     */
    public void setTenant(UUID organizationId) {
        setTenant(organizationId, null);
    }

    /**
     * Activates both tenant and user session context.
     */
    public void setTenant(UUID organizationId, UUID userId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId must not be null");
        }

        log.debug("Setting session context: organizationId={}, userId={}", organizationId, userId);

        // Write to the DB session — affects all subsequent queries on this connection.
        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (java.sql.PreparedStatement ps = conn.prepareStatement(SET_SESSION_CONTEXT_SQL)) {
                ps.setObject(1, organizationId);
                ps.setObject(2, userId);
                ps.execute();
            }
            return null;
        });

        // Mirror at application level for lightweight reads.
        CURRENT_ORG_ID.set(organizationId);
        if (userId != null) {
            CURRENT_USER_ID.set(userId);
        } else {
            CURRENT_USER_ID.remove();
        }
    }

    /**
     * Resets the tenant and user contexts on both the database connection and the
     * application-level thread-locals.
     */
    public void clearTenant() {
        log.debug("Clearing session context (was: org={}, user={})", CURRENT_ORG_ID.get(), CURRENT_USER_ID.get());

        // Reset the DB session setting.
        jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (java.sql.PreparedStatement ps = conn.prepareStatement("SELECT set_session_context(NULL::uuid, NULL::uuid)")) {
                ps.execute();
            }
            return null;
        });

        // Remove from thread-local to prevent memory leaks in thread pools.
        CURRENT_ORG_ID.remove();
        CURRENT_USER_ID.remove();
    }

    /**
     * Returns the organisation UUID currently bound to this thread, or {@code null}.
     */
    public static UUID getCurrentOrgId() {
        return CURRENT_ORG_ID.get();
    }

    /**
     * Returns the user UUID currently bound to this thread, or {@code null}.
     */
    public static UUID getCurrentUserId() {
        return CURRENT_USER_ID.get();
    }
}
