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
     * PostgreSQL function that writes the tenant UUID into the session-level
     * setting {@code app.tenant_id}, which is then read by RLS policies.
     */
    private static final String SET_TENANT_SQL = "SELECT set_tenant_context(?::uuid)";

    /**
     * Clears the tenant context by passing an empty string to the function.
     * The RLS policies must handle an empty / null setting as "no tenant" access.
     */
    private static final String CLEAR_TENANT_SQL = "SELECT set_tenant_context(NULL::uuid)";

    // -------------------------------------------------------------------------
    // Thread-local storage — application-level tenant tracking
    // -------------------------------------------------------------------------

    /**
     * Holds the organisation UUID for the current request thread so that
     * application code can call {@link #getCurrentOrgId()} without a DB call.
     *
     * <p><strong>Important:</strong> Always call {@link #clearTenant()} in a
     * {@code finally} block or a framework cleanup hook to prevent leaking the
     * value to the next request processed by the same thread.</p>
     */
    private static final ThreadLocal<UUID> CURRENT_ORG_ID = new ThreadLocal<>();

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Activates the tenant context for {@code organizationId} on both the
     * database connection and the application-level thread-local.
     *
     * <p>This method is idempotent — calling it multiple times with the same
     * value is safe (though redundant).</p>
     *
     * @param organizationId the tenant UUID to activate; must not be {@code null}
     * @throws IllegalArgumentException if {@code organizationId} is {@code null}
     */
    public void setTenant(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId must not be null");
        }

        log.debug("Setting tenant context: organizationId={}", organizationId);

        // Write to the DB session — affects all subsequent queries on this connection.
        jdbcTemplate.queryForObject(SET_TENANT_SQL, Void.class, organizationId.toString());

        // Mirror at application level for lightweight reads.
        CURRENT_ORG_ID.set(organizationId);
    }

    /**
     * Resets the tenant context on both the database connection and the
     * application-level thread-local.
     *
     * <p>Always call this in a {@code finally} block or a servlet filter to
     * ensure the context is cleaned up even when an exception occurs.</p>
     */
    public void clearTenant() {
        log.debug("Clearing tenant context (was: {})", CURRENT_ORG_ID.get());

        // Reset the DB session setting.
        jdbcTemplate.queryForObject(CLEAR_TENANT_SQL, Void.class);

        // Remove from thread-local to prevent memory leaks in thread pools.
        CURRENT_ORG_ID.remove();
    }

    /**
     * Returns the organisation UUID currently bound to this thread, or
     * {@code null} if no tenant has been activated.
     *
     * <p>This is a pure in-memory read — no database call is made.</p>
     *
     * @return the current organisation UUID, or {@code null}
     */
    public static UUID getCurrentOrgId() {
        return CURRENT_ORG_ID.get();
    }
}
