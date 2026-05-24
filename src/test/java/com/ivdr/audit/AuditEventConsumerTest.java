package com.ivdr.audit;

import com.ivdr.common.util.CryptoUtil;
import com.ivdr.domain.audit.entity.AuditLog;
import com.ivdr.domain.audit.event.AuditEvent;
import com.ivdr.domain.audit.event.AuditEventType;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@code AuditEventConsumer}.
 *
 * <p>Because {@code AuditEventConsumer} is planned (the consumer directory is
 * currently empty in the project) this test file is written against the
 * <em>expected</em> public interface of the consumer.  The consumer class must
 * exist with at minimum:
 * <pre>{@code
 *   public class AuditEventConsumer {
 *       public AuditEventConsumer(AuditLogRepository repo,
 *                                  CryptoUtil crypto,
 *                                  AnomalyDetectionService anomalyService) { … }
 *       public void consume(AuditEvent event, Acknowledgment ack) { … }
 *   }
 * }</pre>
 *
 * <p>Test contract:
 * <ul>
 *   <li>Valid, unique events are persisted and acknowledged.</li>
 *   <li>Duplicate {@code eventId}s are skipped (idempotency) but still acked.</li>
 *   <li>Invalid HMAC signatures still result in a save (flagged for review).</li>
 *   <li>Anomaly detection is invoked for every saved event.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@Slf4j
class AuditEventConsumerTest {

    // -------------------------------------------------------------------------
    // Mocked dependencies
    // -------------------------------------------------------------------------

    /**
     * Repository for persisting {@link AuditLog} rows.
     * Typed as a raw JpaRepository because the concrete AuditLogRepository
     * interface lives in the (currently empty) audit/repository package.
     * Replace with the concrete type once it is created:
     * {@code @Mock AuditLogRepository auditLogRepository;}
     */
    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private CryptoUtil cryptoUtil;

    @Mock
    private AnomalyDetectionService anomalyDetectionService;

    @Mock
    private Acknowledgment acknowledgment;

    // -------------------------------------------------------------------------
    // Subject under test
    // -------------------------------------------------------------------------

    private AuditEventConsumer consumer;

    // -------------------------------------------------------------------------
    // Fixture data
    // -------------------------------------------------------------------------

    private static final UUID ORG_ID    = UUID.randomUUID();
    private static final UUID USER_ID   = UUID.randomUUID();
    private static final UUID EVENT_ID  = UUID.randomUUID();

    private static final String VALID_SIGNATURE   = "validHmacSignature==";
    private static final String INVALID_SIGNATURE = "corruptedSignature!!";

    /** Builds a minimal but valid {@link AuditEvent} with a known eventId. */
    private AuditEvent buildEvent(UUID eventId, String signature) {
        return new AuditEvent(
                eventId,
                AuditEventType.DOCUMENT_UPLOADED,
                ORG_ID,
                USER_ID,
                "document",
                "doc-001",
                "127.0.0.1",
                "Mozilla/5.0",
                Map.of("fileName", "report.pdf"),
                signature,
                Instant.now()
        );
    }

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        consumer = new AuditEventConsumer(
                auditLogRepository,
                cryptoUtil,
                anomalyDetectionService
        );
    }

    // =========================================================================
    // 1. consume_validEvent_persistsToDb
    // =========================================================================

    @Test
    @DisplayName("consume() — valid, unseen event — saves AuditLog and acknowledges")
    void consume_validEvent_persistsToDb() {
        // ── Arrange ──────────────────────────────────────────────────────────
        AuditEvent event = buildEvent(EVENT_ID, VALID_SIGNATURE);

        // Event has NOT been seen before
        when(auditLogRepository.existsByEventId(EVENT_ID)).thenReturn(false);

        // Signature verifies successfully
        when(cryptoUtil.verifyHmac(anyString(), eq(VALID_SIGNATURE))).thenReturn(true);

        // Capture what gets saved
        ArgumentCaptor<AuditLog> savedLogCaptor = ArgumentCaptor.forClass(AuditLog.class);
        when(auditLogRepository.save(savedLogCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        // ── Act ───────────────────────────────────────────────────────────────
        consumer.consume(event, acknowledgment);

        // ── Assert ────────────────────────────────────────────────────────────
        // The row must be persisted
        verify(auditLogRepository).save(any(AuditLog.class));

        AuditLog saved = savedLogCaptor.getValue();
        assertThat(saved.getEventId()).isEqualTo(EVENT_ID);
        assertThat(saved.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getEventType()).isEqualTo(AuditEventType.DOCUMENT_UPLOADED.name());
        assertThat(saved.getResourceType()).isEqualTo("document");
        assertThat(saved.getResourceId()).isEqualTo("doc-001");
        assertThat(saved.isAnomaly()).isFalse();

        // Kafka offset must be committed
        verify(acknowledgment).acknowledge();

        log.info("consume_validEvent_persistsToDb passed: savedEventId={}", saved.getEventId());
    }

    // =========================================================================
    // 2. consume_duplicateEventId_skipsAndAcks
    // =========================================================================

    @Test
    @DisplayName("consume() — duplicate eventId — skips save, still acknowledges (idempotency)")
    void consume_duplicateEventId_skipsAndAcks() {
        // ── Arrange ──────────────────────────────────────────────────────────
        AuditEvent event = buildEvent(EVENT_ID, VALID_SIGNATURE);

        // Duplicate: already exists in the database
        when(auditLogRepository.existsByEventId(EVENT_ID)).thenReturn(true);

        // ── Act ───────────────────────────────────────────────────────────────
        consumer.consume(event, acknowledgment);

        // ── Assert ────────────────────────────────────────────────────────────
        // No persistence attempt on duplicate
        verify(auditLogRepository, never()).save(any());

        // Anomaly detection must NOT run for a skipped event
        verifyNoInteractions(anomalyDetectionService);

        // Must still acknowledge to advance Kafka offset
        verify(acknowledgment).acknowledge();

        log.info("consume_duplicateEventId_skipsAndAcks passed: eventId={}", EVENT_ID);
    }

    // =========================================================================
    // 3. consume_invalidSignature_savesAndFlagsForReview
    // =========================================================================

    @Test
    @DisplayName("consume() — invalid HMAC signature — still saves but logs warning")
    void consume_invalidSignature_savesAndFlagsForReview() {
        // ── Arrange ──────────────────────────────────────────────────────────
        AuditEvent event = buildEvent(EVENT_ID, INVALID_SIGNATURE);

        when(auditLogRepository.existsByEventId(EVENT_ID)).thenReturn(false);

        // Signature verification FAILS
        when(cryptoUtil.verifyHmac(anyString(), eq(INVALID_SIGNATURE))).thenReturn(false);

        ArgumentCaptor<AuditLog> savedLogCaptor = ArgumentCaptor.forClass(AuditLog.class);
        when(auditLogRepository.save(savedLogCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        // ── Act ───────────────────────────────────────────────────────────────
        consumer.consume(event, acknowledgment);

        // ── Assert ────────────────────────────────────────────────────────────
        // Row must still be saved (audit trail is never discarded)
        verify(auditLogRepository).save(any(AuditLog.class));

        AuditLog saved = savedLogCaptor.getValue();
        assertThat(saved.getEventId()).isEqualTo(EVENT_ID);
        // Consumer should flag the record for manual review; implementation can
        // use isAnomaly=true or a separate review flag — we verify isAnomaly here
        // as a reasonable default contract.
        assertThat(saved.isAnomaly())
                .as("Invalid-signature events should be flagged as anomalous")
                .isTrue();

        // Kafka offset still committed
        verify(acknowledgment).acknowledge();

        log.info("consume_invalidSignature_savesAndFlagsForReview passed: eventId={}", EVENT_ID);
    }

    // =========================================================================
    // 4. consume_anomalyDetectionTriggered
    // =========================================================================

    @Test
    @DisplayName("consume() — valid event saved — anomaly detection analyze() is called")
    void consume_anomalyDetectionTriggered() {
        // ── Arrange ──────────────────────────────────────────────────────────
        AuditEvent event = buildEvent(EVENT_ID, VALID_SIGNATURE);

        when(auditLogRepository.existsByEventId(EVENT_ID)).thenReturn(false);
        when(cryptoUtil.verifyHmac(anyString(), eq(VALID_SIGNATURE))).thenReturn(true);

        ArgumentCaptor<AuditLog> savedLogCaptor = ArgumentCaptor.forClass(AuditLog.class);
        when(auditLogRepository.save(savedLogCaptor.capture()))
                .thenAnswer(inv -> inv.getArgument(0));

        // anomalyDetectionService.analyze() is expected to be async (fire-and-forget)
        // so we just verify it is called with the saved AuditLog
        doNothing().when(anomalyDetectionService).analyze(any(AuditLog.class));

        // ── Act ───────────────────────────────────────────────────────────────
        consumer.consume(event, acknowledgment);

        // ── Assert ────────────────────────────────────────────────────────────
        // Verify analyze() was called with the persisted log instance
        ArgumentCaptor<AuditLog> analyzeCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(anomalyDetectionService).analyze(analyzeCaptor.capture());

        AuditLog analyzedLog = analyzeCaptor.getValue();
        assertThat(analyzedLog.getEventId()).isEqualTo(EVENT_ID);
        assertThat(analyzedLog.getOrganizationId()).isEqualTo(ORG_ID);

        // Acknowledgment must follow save + analysis dispatch
        verify(acknowledgment).acknowledge();

        log.info("consume_anomalyDetectionTriggered passed: eventId={}", EVENT_ID);
    }

    // =========================================================================
    // Inner placeholder interfaces
    // =========================================================================
    // These stubs keep the test self-contained until the real classes are created.
    // Once AuditLogRepository and AnomalyDetectionService exist in the production
    // codebase, delete these inner declarations and import the real types above.

    /**
     * Placeholder repository interface — replace with the real
     * {@code com.ivdr.domain.audit.repository.AuditLogRepository} once created.
     */
    interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

        /** Idempotency check: returns {@code true} if the event was already stored. */
        boolean existsByEventId(UUID eventId);
    }

    /**
     * Placeholder service interface — replace with
     * {@code com.ivdr.domain.audit.service.AnomalyDetectionService} once created.
     */
    interface AnomalyDetectionService {

        /**
         * Asynchronously analyses the persisted log entry for anomalous patterns.
         *
         * @param log the freshly persisted audit log entry
         */
        void analyze(AuditLog log);
    }

    /**
     * Placeholder consumer class — delete this inner class and replace with a
     * proper {@code @KafkaListener}-annotated class in
     * {@code com.ivdr.domain.audit.consumer} once implemented.
     *
     * <p>This inner class drives the tests so they compile and validate the
     * desired behavioral contract right now, letting the real implementation
     * be written to pass these tests (test-driven approach).
     */
    static class AuditEventConsumer {

        private final AuditLogRepository       auditLogRepository;
        private final CryptoUtil               cryptoUtil;
        private final AnomalyDetectionService  anomalyDetectionService;

        AuditEventConsumer(AuditLogRepository repo,
                           CryptoUtil crypto,
                           AnomalyDetectionService anomalyService) {
            this.auditLogRepository      = repo;
            this.cryptoUtil              = crypto;
            this.anomalyDetectionService = anomalyService;
        }

        /**
         * Processes one Kafka {@link AuditEvent} message.
         *
         * <ol>
         *   <li>Skip duplicates (idempotency guard on {@code eventId}).</li>
         *   <li>Verify the HMAC signature; flag record if invalid.</li>
         *   <li>Persist the {@link AuditLog} row.</li>
         *   <li>Dispatch to anomaly detection.</li>
         *   <li>Acknowledge the Kafka offset.</li>
         * </ol>
         *
         * @param event the deserialized Kafka message
         * @param ack   manual-commit acknowledgment handle
         */
        public void consume(AuditEvent event, Acknowledgment ack) {
            // 1. Idempotency guard
            if (auditLogRepository.existsByEventId(event.eventId())) {
                log.warn("[AuditConsumer] Duplicate eventId={} — skipping", event.eventId());
                ack.acknowledge();
                return;
            }

            // 2. Signature verification
            //    Build a canonical payload string for HMAC verification.
            String canonicalPayload = event.eventId() + "|"
                    + event.eventType() + "|"
                    + event.organizationId() + "|"
                    + event.occurredAt();

            boolean signatureValid = (event.signature() != null)
                    && cryptoUtil.verifyHmac(canonicalPayload, event.signature());

            if (!signatureValid) {
                log.warn("[AuditConsumer] Invalid signature for eventId={} — flagging for review",
                        event.eventId());
            }

            // 3. Build and persist the AuditLog row
            AuditLog auditLog = AuditLog.builder()
                    .eventId(event.eventId())
                    .organizationId(event.organizationId())
                    .userId(event.userId())
                    .eventType(event.eventType().name())
                    .resourceType(event.resourceType())
                    .resourceId(event.resourceId())
                    .ipAddress(event.ipAddress())
                    .userAgent(event.userAgent())
                    .metadata(event.metadata())
                    .signature(event.signature())
                    .isAnomaly(!signatureValid)   // flag invalid-sig records immediately
                    .build();

            AuditLog saved = auditLogRepository.save(auditLog);

            // 4. Fire-and-forget anomaly detection
            anomalyDetectionService.analyze(saved);

            // 5. Commit the Kafka offset
            ack.acknowledge();
        }
    }
}
