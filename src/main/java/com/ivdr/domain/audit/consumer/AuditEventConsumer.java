package com.ivdr.domain.audit.consumer;

import com.ivdr.domain.audit.entity.AuditLog;
import com.ivdr.domain.audit.event.AuditEvent;
import com.ivdr.domain.audit.repository.AuditLogRepository;
import com.ivdr.domain.audit.service.AnomalyDetectionService;
import com.ivdr.common.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Kafka consumer that processes {@link AuditEvent} messages from the audit topic.
 *
 * <h3>Processing pipeline</h3>
 * <ol>
 *   <li><strong>Idempotency check</strong> – if the {@code eventId} already exists
 *       in the database the message is acknowledged and discarded.</li>
 *   <li><strong>Signature verification</strong> – HMAC-SHA-256 is recomputed and
 *       compared to the value carried in the message; a mismatch raises an exception
 *       (routed to the DLQ).</li>
 *   <li><strong>Persistence</strong> – the event is mapped to an {@link AuditLog}
 *       entity and saved.</li>
 *   <li><strong>Anomaly detection</strong> – {@link AnomalyDetectionService#analyze}
 *       is invoked asynchronously so that the detection logic does not block offset
 *       acknowledgement.</li>
 *   <li><strong>Acknowledgement</strong> – the Kafka offset is committed only after
 *       successful persistence.</li>
 * </ol>
 *
 * <h3>Error handling</h3>
 * Any unhandled exception is logged and re-thrown so that the configured
 * Dead-Letter-Queue (DLQ) error handler can route the message accordingly.
 * Manual acknowledgement ({@code AckMode.MANUAL}) ensures that unprocessed
 * messages are not silently skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventConsumer {

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final AuditLogRepository auditLogRepository;
    private final AnomalyDetectionService anomalyDetectionService;
    private final CryptoUtil cryptoUtil;

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /**
     * HMAC signing key used by the producer; resolved from
     * {@code app.audit.signing-key} in {@code application.yml}.
     */
    @Value("${app.audit.signing-key}")
    private String signingKey;

    // -------------------------------------------------------------------------
    // Kafka listener
    // -------------------------------------------------------------------------

    /**
     * Consumes an {@link AuditEvent} from the audit-events Kafka topic.
     *
     * <p>The {@code containerFactory} must be configured with:
     * <ul>
     *   <li>{@code AckMode.MANUAL} (so this method controls offset commits)</li>
     *   <li>A {@code DefaultErrorHandler} pointing at the DLQ</li>
     *   <li>A {@code JsonDeserializer} bound to {@link AuditEvent}</li>
     * </ul>
     *
     * @param record The raw Kafka consumer record (used for logging offsets).
     * @param event  The deserialised audit event payload.
     * @param ack    Manual acknowledgement handle.
     */
    @KafkaListener(
            topics = "${app.kafka.topics.audit-events}",
            containerFactory = "auditKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, AuditEvent> record,
                        AuditEvent event,
                        Acknowledgment ack) {

        log.debug(
                "[AuditConsumer] Received event. topic={}, partition={}, offset={}, " +
                "eventId={}, eventType={}",
                record.topic(), record.partition(), record.offset(),
                event.eventId(), event.eventType()
        );

        try {
            // ------------------------------------------------------------------
            // Step 1 – Idempotency guard
            // ------------------------------------------------------------------
            if (auditLogRepository.existsByEventId(event.eventId())) {
                log.warn(
                        "[AuditConsumer] Duplicate event detected – skipping. " +
                        "eventId={}, offset={}",
                        event.eventId(), record.offset()
                );
                ack.acknowledge();
                return;
            }

            // ------------------------------------------------------------------
            // Step 2 – Signature verification
            // ------------------------------------------------------------------
            verifySignature(event);

            // ------------------------------------------------------------------
            // Step 3 – Map event → entity and persist
            // ------------------------------------------------------------------
            AuditLog auditLog = mapToEntity(event);
            auditLogRepository.save(auditLog);

            log.info(
                    "[AuditConsumer] Persisted audit log. eventId={}, eventType={}, orgId={}",
                    event.eventId(), event.eventType(), event.organizationId()
            );

            // ------------------------------------------------------------------
            // Step 4 – Trigger async anomaly detection (non-blocking)
            // ------------------------------------------------------------------
            anomalyDetectionService.analyze(event);

            // ------------------------------------------------------------------
            // Step 5 – Acknowledge offset only after successful persistence
            // ------------------------------------------------------------------
            ack.acknowledge();

        } catch (Exception ex) {
            // Log full stack trace so the ops team can diagnose DLQ entries.
            log.error(
                    "[AuditConsumer] Failed to process audit event – routing to DLQ. " +
                    "eventId={}, eventType={}, topic={}, partition={}, offset={}, error={}",
                    event.eventId(), event.eventType(),
                    record.topic(), record.partition(), record.offset(),
                    ex.getMessage(), ex
            );
            // Re-throw: the configured DefaultErrorHandler will route to the DLQ
            // after exhausting retries.
            throw ex;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Recomputes the HMAC-SHA-256 signature over the canonical payload fields
     * and compares it to the signature embedded in the event.
     *
     * @param event The event whose signature is to be verified.
     * @throws SecurityException if the signature is missing or does not match.
     */
    private void verifySignature(AuditEvent event) {
        if (event.signature() == null || event.signature().isBlank()) {
            throw new SecurityException(
                    "Audit event is missing its HMAC signature. eventId=" + event.eventId());
        }

        String canonicalPayload = String.join("|",
                nvl(event.eventId()),
                nvl(event.eventType()),
                nvl(event.organizationId()),
                nvl(event.userId()),
                nvl(event.resourceType()),
                nvl(event.resourceId()),
                nvl(event.occurredAt())
        );

        String expectedSignature = cryptoUtil.hmacSha256(canonicalPayload, signingKey);

        if (!expectedSignature.equals(event.signature())) {
            throw new SecurityException(
                    "Audit event HMAC signature mismatch – possible tampering detected. " +
                    "eventId=" + event.eventId()
            );
        }
    }

    /**
     * Maps an {@link AuditEvent} (Kafka DTO) to an {@link AuditLog} (JPA entity).
     *
     * <p>{@code isAnomaly} defaults to {@code false}; the
     * {@link AnomalyDetectionService} may flip this flag after analysis.
     *
     * @param event The source event.
     * @return A populated, unsaved {@link AuditLog}.
     */
    private AuditLog mapToEntity(AuditEvent event) {
        return AuditLog.builder()
                .eventId(event.eventId())
                .eventType(event.eventType().name())
                .organizationId(event.organizationId())
                .userId(event.userId())
                .resourceType(event.resourceType())
                .resourceId(event.resourceId())
                .ipAddress(event.ipAddress())
                .userAgent(event.userAgent())
                .metadata(event.metadata())
                .signature(event.signature())
                .isAnomaly(false)
                // createdAt is set by @PrePersist; we still honour the original
                // event timestamp by storing it in createdAt for accuracy.
                .createdAt(event.occurredAt() != null
                        ? LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC)
                        : LocalDateTime.now())
                .build();
    }

    /** Null-safe {@link Object#toString()} helper. */
    private String nvl(Object value) {
        return value != null ? value.toString() : "";
    }
}
