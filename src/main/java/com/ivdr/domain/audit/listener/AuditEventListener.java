package com.ivdr.domain.audit.listener;

import com.ivdr.domain.audit.event.AuditEvent;
import com.ivdr.domain.audit.producer.AuditEventProducer;
import com.ivdr.domain.document.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Event listener that forwards Spring application document audit events to Kafka.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final AuditEventProducer auditEventProducer;

    @EventListener
    public void handleDocumentAuditEvent(DocumentService.DocumentAuditEvent springEvent) {
        log.debug("[AuditEventListener] Forwarding document audit event to Kafka: type={}, docId={}",
                springEvent.eventType(), springEvent.documentId());

        try {
            AuditEvent kafkaEvent = AuditEvent.of(
                    springEvent.eventType(),
                    springEvent.organizationId(),
                    springEvent.userId(),
                    "document",
                    springEvent.documentId() != null ? springEvent.documentId().toString() : "",
                    Map.of("workspaceId", springEvent.workspaceId() != null ? springEvent.workspaceId().toString() : "")
            );

            auditEventProducer.publishAuditEvent(kafkaEvent);
        } catch (Exception ex) {
            log.error("Failed to forward DocumentAuditEvent to Kafka: {}", ex.getMessage(), ex);
        }
    }
}
