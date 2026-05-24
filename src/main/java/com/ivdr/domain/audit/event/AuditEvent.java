package com.ivdr.domain.audit.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable Kafka message payload representing a single audit event.
 *
 * <p>Instances are serialized to JSON before being published to the
 * {@code audit-events} Kafka topic and deserialized by {@code AuditEventConsumer}.
 * All fields are nullable except {@code eventId}, {@code eventType}, and
 * {@code occurredAt}, which are always present.
 *
 * <p>The record is intentionally append-only: once created it must not be mutated.
 * The {@code signature} field is populated by {@code AuditEventProducer} just
 * before publishing (HMAC-SHA-256 of the canonical payload).
 *
 * @param eventId       Globally unique identifier for this event (UUID v4).
 * @param eventType     The type of action that was performed.
 * @param organizationId The tenant/org the actor belongs to.
 * @param userId        The user who triggered the action (may be {@code null}
 *                      for system-generated events).
 * @param resourceType  Logical type of the affected resource (e.g. {@code "document"}).
 * @param resourceId    Opaque identifier of the affected resource.
 * @param ipAddress     Source IP address of the request, if available.
 * @param userAgent     HTTP User-Agent header, if available.
 * @param metadata      Arbitrary key-value context (document name, workspace id, …).
 * @param signature     HMAC-SHA-256 signature computed over the payload by the producer.
 * @param occurredAt    Wall-clock time at which the event took place.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEvent(

        UUID eventId,

        AuditEventType eventType,

        UUID organizationId,

        UUID userId,

        String resourceType,

        String resourceId,

        String ipAddress,

        String userAgent,

        Map<String, Object> metadata,

        String signature,

        @JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
        Instant occurredAt

) {

    // -------------------------------------------------------------------------
    // Compact canonical constructor – defensive copies & validation
    // -------------------------------------------------------------------------

    /**
     * Canonical constructor; performs lightweight validation and makes a
     * defensive, unmodifiable copy of {@code metadata}.
     */
    public AuditEvent {
        if (eventId == null)   throw new IllegalArgumentException("eventId must not be null");
        if (eventType == null) throw new IllegalArgumentException("eventType must not be null");
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt must not be null");

        // Defensive copy: callers cannot mutate the map after construction.
        metadata = (metadata != null)
                ? Collections.unmodifiableMap(Map.copyOf(metadata))
                : Collections.emptyMap();
    }

    // -------------------------------------------------------------------------
    // Static factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates an {@code AuditEvent} with only the essential fields populated.
     *
     * <p>Network-level fields ({@code ipAddress}, {@code userAgent}) and the
     * cryptographic {@code signature} are left {@code null}; they are set by
     * the producer layer before the event is published to Kafka.
     *
     * @param type         The type of the event.
     * @param orgId        Organisation/tenant identifier.
     * @param userId       The acting user's identifier.
     * @param resourceType Logical resource category.
     * @param resourceId   Identifier of the specific resource.
     * @param metadata     Additional contextual key-value pairs.
     * @return A new, unsigned {@code AuditEvent}.
     */
    public static AuditEvent of(
            AuditEventType type,
            UUID orgId,
            UUID userId,
            String resourceType,
            String resourceId,
            Map<String, Object> metadata) {

        return new AuditEvent(
                UUID.randomUUID(),
                type,
                orgId,
                userId,
                resourceType,
                resourceId,
                null,   // ipAddress – enriched by producer/filter
                null,   // userAgent – enriched by producer/filter
                metadata,
                null,   // signature – set by AuditEventProducer
                Instant.now()
        );
    }

    /**
     * Returns a copy of this event with the given {@code signature} attached.
     *
     * <p>Used by {@code AuditEventProducer} after computing the HMAC to avoid
     * mutating the original instance.
     *
     * @param computedSignature The HMAC-SHA-256 signature string.
     * @return A new {@code AuditEvent} identical to this one but with the
     *         provided {@code signature}.
     */
    public AuditEvent withSignature(String computedSignature) {
        return new AuditEvent(
                eventId,
                eventType,
                organizationId,
                userId,
                resourceType,
                resourceId,
                ipAddress,
                userAgent,
                metadata,
                computedSignature,
                occurredAt
        );
    }

    /**
     * Returns a copy of this event enriched with request-level network fields.
     *
     * @param ip  The source IP address.
     * @param ua  The HTTP User-Agent string.
     * @return A new {@code AuditEvent} with {@code ipAddress} and {@code userAgent} set.
     */
    public AuditEvent withNetworkInfo(String ip, String ua) {
        return new AuditEvent(
                eventId,
                eventType,
                organizationId,
                userId,
                resourceType,
                resourceId,
                ip,
                ua,
                metadata,
                signature,
                occurredAt
        );
    }
}
