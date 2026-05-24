package com.ivdr.domain.audit.service;

import com.ivdr.domain.audit.entity.AuditLog;
import com.ivdr.domain.audit.event.AuditEvent;
import com.ivdr.domain.audit.event.AuditEventType;
import com.ivdr.domain.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Asynchronous service that evaluates incoming {@link AuditEvent} instances
 * against a set of threshold-based anomaly-detection rules.
 *
 * <h3>Implemented rules</h3>
 * <ol>
 *   <li><strong>Excessive downloads</strong> – if a user downloads more than
 *       {@code app.anomaly.thresholds.download-per-hour} documents within a
 *       rolling 60-minute window, the event cluster is flagged as anomalous.</li>
 *   <li><strong>Repeated login failures</strong> – if a user fails authentication
 *       more than 5 times within the last 60 seconds, an account-lock signal is
 *       emitted and the events are flagged.</li>
 *   <li><strong>Document view enumeration</strong> – if a user views more than
 *       {@code app.anomaly.thresholds.doc-views-per-hour} distinct documents
 *       within an hour, a data-exfiltration attempt may be in progress.</li>
 * </ol>
 *
 * <h3>Output</h3>
 * When a rule fires the service:
 * <ul>
 *   <li>Persists a synthetic {@link AuditLog} row with {@code isAnomaly=true}
 *       and {@code eventType=ANOMALY_DETECTED}.</li>
 *   <li>Broadcasts a WebSocket alert to the destination
 *       {@code /queue/alerts/{userId}} via {@link SimpMessagingTemplate}.</li>
 * </ul>
 *
 * <p>All methods run in a dedicated async thread pool (configured via
 * {@code @EnableAsync} and a {@code TaskExecutor} bean). Failures inside this
 * service do <em>not</em> affect the Kafka consumer's offset acknowledgement.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final AuditLogRepository auditLogRepository;

    /**
     * Spring WebSocket messaging template used to push real-time alerts
     * to connected browser clients.
     */
    private final SimpMessagingTemplate messagingTemplate;

    // -------------------------------------------------------------------------
    // Threshold configuration (injected from application.yml)
    // -------------------------------------------------------------------------

    /**
     * Maximum number of document downloads allowed per user per rolling hour
     * before the activity is classified as anomalous.
     * Resolved from {@code app.anomaly.thresholds.download-per-hour}.
     */
    @Value("${app.anomaly.thresholds.download-per-hour:20}")
    private int downloadPerHourThreshold;

    /**
     * Maximum number of document views allowed per user per rolling hour.
     * Resolved from {@code app.anomaly.thresholds.doc-views-per-hour:50}.
     */
    @Value("${app.anomaly.thresholds.doc-views-per-hour:50}")
    private int docViewsPerHourThreshold;

    /**
     * Maximum number of consecutive login failures allowed per user within
     * 60 seconds before an account-lock signal is raised.
     * Hard-coded to 5 per OWASP recommendations; override with
     * {@code app.anomaly.thresholds.login-failed-per-minute}.
     */
    @Value("${app.anomaly.thresholds.login-failed-per-minute:5}")
    private int loginFailedPerMinuteThreshold;

    // -------------------------------------------------------------------------
    // Public async API
    // -------------------------------------------------------------------------

    /**
     * Evaluates the given audit event against all configured anomaly rules.
     *
     * <p>This method is annotated with {@link Async} and therefore executes
     * on a background thread from the configured async executor. The Kafka
     * consumer does <em>not</em> wait for this method to complete before
     * acknowledging the offset.
     *
     * @param event The audit event to analyse. Must not be {@code null}.
     */
    @Async
    public void analyze(AuditEvent event) {
        if (event == null || event.userId() == null) {
            return; // System events without a user context are skipped.
        }

        try {
            switch (event.eventType()) {
                case DOCUMENT_DOWNLOADED -> checkDownloadAnomaly(event);
                case LOGIN_FAILED        -> checkLoginFailureAnomaly(event);
                case DOCUMENT_VIEWED     -> checkDocumentViewAnomaly(event);
                default -> {
                    // No rule defined for this event type; nothing to do.
                }
            }
        } catch (Exception ex) {
            // Anomaly detection must never propagate exceptions back to the caller.
            log.error(
                    "[AnomalyDetection] Unexpected error during analysis. " +
                    "eventId={}, eventType={}, userId={}, error={}",
                    event.eventId(), event.eventType(), event.userId(), ex.getMessage(), ex
            );
        }
    }

    // -------------------------------------------------------------------------
    // Rule implementations
    // -------------------------------------------------------------------------

    /**
     * Rule 1 – Excessive document downloads in a rolling 1-hour window.
     *
     * <p>If the number of {@code DOCUMENT_DOWNLOADED} events for the user in
     * the last hour exceeds {@link #downloadPerHourThreshold}, an anomaly is
     * recorded and a WebSocket alert is sent to the user's alert queue.
     *
     * @param event The triggering download event.
     */
    private void checkDownloadAnomaly(AuditEvent event) {
        LocalDateTime windowStart = LocalDateTime.now().minusHours(1);
        long count = auditLogRepository.countByUserIdAndEventTypeAndCreatedAtAfter(
                event.userId(),
                AuditEventType.DOCUMENT_DOWNLOADED.name(),
                windowStart
        );

        log.debug("[AnomalyDetection] Download count in last hour: userId={}, count={}",
                event.userId(), count);

        if (count > downloadPerHourThreshold) {
            String message = String.format(
                    "Anomaly detected: User %s downloaded %d documents in the last hour " +
                    "(threshold=%d).", event.userId(), count, downloadPerHourThreshold);

            log.warn("[AnomalyDetection] {}", message);

            persistAnomaly(event, message, Map.of(
                    "rule", "EXCESSIVE_DOWNLOADS",
                    "count", count,
                    "threshold", downloadPerHourThreshold,
                    "windowHours", 1
            ));

            broadcastAlert(event, "EXCESSIVE_DOWNLOADS", message);
        }
    }

    /**
     * Rule 2 – Repeated login failures within 60 seconds.
     *
     * <p>If the user has failed authentication more than
     * {@link #loginFailedPerMinuteThreshold} times in the last minute, an
     * anomaly is recorded and an account-lock signal is broadcast. The
     * actual account locking is handled by the authentication service when
     * it receives the WebSocket / event notification.
     *
     * @param event The triggering login-failure event.
     */
    private void checkLoginFailureAnomaly(AuditEvent event) {
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(1);
        long count = auditLogRepository.countByUserIdAndEventTypeAndCreatedAtAfter(
                event.userId(),
                AuditEventType.LOGIN_FAILED.name(),
                windowStart
        );

        log.debug("[AnomalyDetection] Login failure count in last minute: userId={}, count={}",
                event.userId(), count);

        if (count > loginFailedPerMinuteThreshold) {
            String message = String.format(
                    "Security alert: User %s has failed login %d times in the last minute " +
                    "(threshold=%d). Account lock signal raised.",
                    event.userId(), count, loginFailedPerMinuteThreshold);

            log.warn("[AnomalyDetection] {}", message);

            persistAnomaly(event, message, Map.of(
                    "rule", "BRUTE_FORCE_LOGIN",
                    "count", count,
                    "threshold", loginFailedPerMinuteThreshold,
                    "windowMinutes", 1,
                    "accountLockSignal", true
            ));

            // Broadcast the lock signal; the auth service subscribes to this topic.
            broadcastAlert(event, "ACCOUNT_LOCK_SIGNAL", message);
        }
    }

    /**
     * Rule 3 – Excessive document views (possible enumeration / data reconnaissance).
     *
     * <p>If the number of {@code DOCUMENT_VIEWED} events for the user in the
     * last hour exceeds {@link #docViewsPerHourThreshold}, an anomaly is recorded.
     *
     * @param event The triggering document-viewed event.
     */
    private void checkDocumentViewAnomaly(AuditEvent event) {
        LocalDateTime windowStart = LocalDateTime.now().minusHours(1);

        // Retrieve the raw rows so we can count *distinct* document IDs.
        List<AuditLog> recentViews = auditLogRepository
                .findByUserIdAndEventTypeAndCreatedAtAfter(
                        event.userId(),
                        AuditEventType.DOCUMENT_VIEWED.name(),
                        windowStart
                );

        long distinctDocs = recentViews.stream()
                .map(AuditLog::getResourceId)
                .distinct()
                .count();

        log.debug(
                "[AnomalyDetection] Document view count in last hour: " +
                "userId={}, distinctDocs={}", event.userId(), distinctDocs);

        if (distinctDocs > docViewsPerHourThreshold) {
            String message = String.format(
                    "Anomaly detected: User %s viewed %d distinct documents in the last hour " +
                    "(threshold=%d). Possible data reconnaissance.",
                    event.userId(), distinctDocs, docViewsPerHourThreshold);

            log.warn("[AnomalyDetection] {}", message);

            persistAnomaly(event, message, Map.of(
                    "rule", "EXCESSIVE_DOC_VIEWS",
                    "distinctDocuments", distinctDocs,
                    "threshold", docViewsPerHourThreshold,
                    "windowHours", 1
            ));

            broadcastAlert(event, "EXCESSIVE_DOC_VIEWS", message);
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /**
     * Persists a synthetic {@link AuditLog} row representing the detected anomaly.
     *
     * <p>The row has {@code eventType=ANOMALY_DETECTED} and {@code isAnomaly=true}.
     * It is linked back to the triggering event via the {@code metadata} map.
     *
     * @param triggeringEvent The original event that caused the anomaly.
     * @param description     Human-readable description of the anomaly.
     * @param ruleMetadata    Additional key-value pairs describing the rule outcome.
     */
    private void persistAnomaly(AuditEvent triggeringEvent,
                                String description,
                                Map<String, Object> ruleMetadata) {
        AuditLog anomalyLog = AuditLog.builder()
                .eventId(java.util.UUID.randomUUID())
                .eventType(AuditEventType.ANOMALY_DETECTED.name())
                .organizationId(triggeringEvent.organizationId())
                .userId(triggeringEvent.userId())
                .resourceType(triggeringEvent.resourceType())
                .resourceId(triggeringEvent.resourceId())
                .isAnomaly(true)
                .metadata(Map.of(
                        "description", description,
                        "triggeringEventId", triggeringEvent.eventId().toString(),
                        "triggeringEventType", triggeringEvent.eventType().name(),
                        "ruleDetails", ruleMetadata
                ))
                .build();
        // @PrePersist sets createdAt.
        auditLogRepository.save(anomalyLog);

        log.info("[AnomalyDetection] Anomaly log persisted. userId={}, orgId={}",
                triggeringEvent.userId(), triggeringEvent.organizationId());
    }

    /**
     * Broadcasts a real-time WebSocket alert to the user's personal alert queue.
     *
     * <p>The destination follows the pattern {@code /queue/alerts} with a
     * {@code simpSessionAttributes}-based user-specific routing.  The
     * front-end subscribes to {@code /user/queue/alerts} which Spring's
     * {@code SimpMessagingTemplate.convertAndSendToUser} automatically resolves.
     *
     * @param event       The triggering audit event (provides userId / orgId).
     * @param alertType   Short code identifying the alert type (e.g.
     *                    {@code "EXCESSIVE_DOWNLOADS"}).
     * @param description Human-readable alert message.
     */
    private void broadcastAlert(AuditEvent event, String alertType, String description) {
        if (event.userId() == null) {
            return; // Cannot route without a user principal.
        }

        Map<String, Object> alertPayload = Map.of(
                "alertType", alertType,
                "description", description,
                "userId", event.userId().toString(),
                "organizationId", event.organizationId() != null
                        ? event.organizationId().toString() : "",
                "eventType", event.eventType().name(),
                "timestamp", java.time.Instant.now().toString()
        );

        try {
            messagingTemplate.convertAndSendToUser(
                    event.userId().toString(),
                    "/queue/alerts",
                    alertPayload
            );
            log.debug("[AnomalyDetection] WebSocket alert sent. userId={}, alertType={}",
                    event.userId(), alertType);
        } catch (Exception ex) {
            // If the user is not connected the message is silently dropped.
            log.warn(
                    "[AnomalyDetection] Could not send WebSocket alert (user possibly " +
                    "not connected). userId={}, alertType={}, error={}",
                    event.userId(), alertType, ex.getMessage()
            );
        }
    }
}
