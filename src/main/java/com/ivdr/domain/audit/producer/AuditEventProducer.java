package com.ivdr.domain.audit.producer;

import com.ivdr.domain.audit.event.AuditEvent;
import com.ivdr.common.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka producer responsible for publishing {@link AuditEvent} messages.
 *
 * <p>Before publishing, the event is signed with an HMAC-SHA-256 digest
 * so that the consumer can verify integrity and detect tampering in transit.
 *
 * <p><strong>Resilience contract:</strong> publishing failures are <em>logged</em>
 * but never re-thrown. Audit event delivery must never interrupt the primary
 * business flow; a separate reconciliation job (or the Kafka {@code acks} /
 * retry configuration) handles reliability guarantees.
 *
 * <p>Kafka messages are keyed by {@code organizationId}, which ensures that all
 * events for a given tenant land on the same partition and are consumed in order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventProducer {

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    /** Kafka template typed to {@code <partitionKey, payload>}. */
    private final KafkaTemplate<String, AuditEvent> kafkaTemplate;
    private final CryptoUtil cryptoUtil;

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /**
     * Kafka topic name for audit events.
     * Resolved from {@code app.kafka.topics.audit-events} in {@code application.yml}.
     */
    @Value("${app.kafka.topics.audit-events}")
    private String auditTopic;

    /**
     * HMAC secret key used to sign events before publishing.
     * Resolved from {@code app.audit.signing-key} in {@code application.yml}.
     * Must be at least 32 bytes when decoded.
     */
    @Value("${app.audit.signing-key}")
    private String signingKey;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Signs the given {@link AuditEvent} with an HMAC-SHA-256 digest and
     * publishes it asynchronously to the audit Kafka topic.
     *
     * <p>The Kafka message key is set to {@code event.organizationId().toString()}
     * to guarantee partition affinity: all events for the same tenant are
     * written to the same partition and therefore consumed in order.
     *
     * <p>Any exception during signing or publishing is caught and logged at
     * {@code ERROR} level. The exception is <em>not</em> propagated, so the
     * calling service's transaction and response are unaffected.
     *
     * @param event The (unsigned) audit event to publish. Must not be {@code null}.
     */
    public void publishAuditEvent(AuditEvent event) {
        if (event == null) {
            log.warn("[Audit] Attempted to publish a null AuditEvent – skipping.");
            return;
        }

        try {
            // 1. Build a canonical string representation of the event for signing.
            //    We deliberately exclude the 'signature' field itself.
            String payload = buildSignablePayload(event);

            // 2. Compute HMAC-SHA-256 signature.
            String signature = cryptoUtil.hmacSha256(payload, signingKey);

            // 3. Attach the signature to a new (immutable) event copy.
            AuditEvent signedEvent = event.withSignature(signature);

            // 4. Determine the Kafka partition key (org-level affinity).
            String partitionKey = event.organizationId() != null
                    ? event.organizationId().toString()
                    : event.eventId().toString(); // fallback for system events

            // 5. Publish asynchronously and attach outcome callbacks for observability.
            CompletableFuture<SendResult<String, AuditEvent>> future =
                    kafkaTemplate.send(auditTopic, partitionKey, signedEvent);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error(
                            "[Audit] Failed to deliver event to Kafka. " +
                            "eventId={}, eventType={}, topic={}, error={}",
                            event.eventId(), event.eventType(), auditTopic, ex.getMessage(), ex
                    );
                } else {
                    log.debug(
                            "[Audit] Event published successfully. " +
                            "eventId={}, eventType={}, topic={}, partition={}, offset={}",
                            event.eventId(),
                            event.eventType(),
                            auditTopic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset()
                    );
                }
            });

        } catch (Exception ex) {
            // Catch-all: signing errors, serialisation failures, etc.
            // Intentionally swallowed so the calling flow is not disrupted.
            log.error(
                    "[Audit] Unexpected error while preparing/publishing audit event. " +
                    "eventId={}, eventType={}, error={}",
                    event.eventId(), event.eventType(), ex.getMessage(), ex
            );
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a deterministic, canonical string over the fields that must be
     * covered by the HMAC signature.
     *
     * <p>The signature intentionally excludes {@code ipAddress} and
     * {@code userAgent} (network-level enrichments that may be added after
     * initial event creation) and the {@code signature} field itself.
     *
     * @param event The event to serialise for signing.
     * @return A {@code '|'}-delimited string of canonical field values.
     */
    private String buildSignablePayload(AuditEvent event) {
        return String.join("|",
                nvl(event.eventId()),
                nvl(event.eventType()),
                nvl(event.organizationId()),
                nvl(event.userId()),
                nvl(event.resourceType()),
                nvl(event.resourceId()),
                nvl(event.occurredAt())
        );
    }

    /** Null-safe {@link Object#toString()} helper. */
    private String nvl(Object value) {
        return value != null ? value.toString() : "";
    }
}
