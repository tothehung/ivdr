package com.ivdr.domain.audit.event;

/**
 * Enumeration of all trackable audit event types within the IVDR platform.
 *
 * <p>Events are grouped by category:
 * <ul>
 *   <li>Authentication events (login, logout, failures, locks)</li>
 *   <li>Document lifecycle events (upload, download, delete, view, AI summarization)</li>
 *   <li>Workspace management events</li>
 *   <li>Security / anomaly events</li>
 * </ul>
 *
 * <p>High-severity events trigger immediate anomaly-detection paths and may
 * generate real-time WebSocket alerts to administrators.
 */
public enum AuditEventType {

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    /** A new user account was created and registered. */
    USER_REGISTERED,

    /** A user successfully authenticated. */
    USER_LOGIN,

    /** A user explicitly ended their session. */
    USER_LOGOUT,

    /** An authentication attempt failed (bad credentials, expired token, etc.). */
    LOGIN_FAILED,

    /** A user account was locked, typically after repeated failed logins. */
    ACCOUNT_LOCKED,

    // -------------------------------------------------------------------------
    // Document lifecycle
    // -------------------------------------------------------------------------

    /** A document was uploaded to the platform. */
    DOCUMENT_UPLOADED,

    /** A document file was downloaded by a user. */
    DOCUMENT_DOWNLOADED,

    /** A document was permanently deleted. */
    DOCUMENT_DELETED,

    /** A document was opened / viewed (read access). */
    DOCUMENT_VIEWED,

    /** An AI summarization was requested and completed for a document. */
    DOCUMENT_AI_SUMMARIZED,

    /** A document's metadata was updated. */
    DOCUMENT_UPDATED,

    // -------------------------------------------------------------------------
    // Workspace management
    // -------------------------------------------------------------------------

    /** A new workspace was created. */
    WORKSPACE_CREATED,

    /** A workspace was deleted. */
    WORKSPACE_DELETED,

    /** A member was added to a workspace. */
    WORKSPACE_MEMBER_ADDED,

    /** A member was removed from a workspace. */
    WORKSPACE_MEMBER_REMOVED,

    // -------------------------------------------------------------------------
    // Security / anomaly
    // -------------------------------------------------------------------------

    /** An operation was rejected due to insufficient permissions. */
    PERMISSION_DENIED,

    /** The anomaly-detection engine flagged suspicious behaviour. */
    ANOMALY_DETECTED,

    /** A client exceeded the configured API or action rate limit. */
    RATE_LIMIT_EXCEEDED;

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if this event type is considered high-severity.
     *
     * <p>High-severity events bypass normal buffering in the anomaly-detection
     * pipeline and are surfaced as real-time alerts.
     *
     * @return {@code true} for {@code ANOMALY_DETECTED}, {@code ACCOUNT_LOCKED},
     *         {@code PERMISSION_DENIED}, and {@code LOGIN_FAILED}
     */
    public boolean isHighSeverity() {
        return this == ANOMALY_DETECTED
                || this == ACCOUNT_LOCKED
                || this == PERMISSION_DENIED
                || this == LOGIN_FAILED;
    }
}
