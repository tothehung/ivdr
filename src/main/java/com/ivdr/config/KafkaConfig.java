package com.ivdr.config;

import com.ivdr.domain.audit.event.AuditEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration: topics, producer, consumer, and Dead Letter Queue (DLQ).
 *
 * <p>Audit pipeline design:
 * API action → AuditEventProducer → Kafka topic (3 partitions)
 *            → AuditEventConsumer → persist to audit_logs
 *            → (on failure) → DLQ topic → alert
 */
@Configuration
public class KafkaConfig {

    @Value("${app.kafka.topics.audit-events}")
    private String auditEventsTopic;

    @Value("${app.kafka.topics.audit-dlq}")
    private String auditDlqTopic;

    @Value("${app.kafka.topics.presence-events}")
    private String presenceTopic;

    @Value("${app.kafka.partitions:3}")
    private int partitions;

    @Value("${app.kafka.replication-factor:1}")
    private short replicationFactor;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ----------------------------------------------------------------
    // Topics
    // ----------------------------------------------------------------

    @Bean
    public NewTopic auditEventsTopic() {
        return TopicBuilder.name(auditEventsTopic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic auditDlqTopic() {
        return TopicBuilder.name(auditDlqTopic)
                .partitions(1)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic presenceEventsTopic() {
        return TopicBuilder.name(presenceTopic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }

    // ----------------------------------------------------------------
    // Producer Factory
    // ----------------------------------------------------------------

    @Bean
    public ProducerFactory<String, AuditEvent> auditEventProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, AuditEvent> auditEventKafkaTemplate() {
        return new KafkaTemplate<>(auditEventProducerFactory());
    }

    // ----------------------------------------------------------------
    // Consumer Factory + Error Handler with DLQ
    // ----------------------------------------------------------------

    @Bean
    public ConsumerFactory<String, AuditEvent> auditEventConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, "ivdr-audit-group");
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringDeserializer.class);
        config.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                org.springframework.kafka.support.serializer.JsonDeserializer.class);
        config.put(org.springframework.kafka.support.serializer.JsonDeserializer.TRUSTED_PACKAGES, "com.ivdr.*");
        config.put(org.springframework.kafka.support.serializer.JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        config.put(org.springframework.kafka.support.serializer.JsonDeserializer.VALUE_DEFAULT_TYPE,
                AuditEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AuditEvent> auditKafkaListenerContainerFactory(
            ConsumerFactory<String, AuditEvent> consumerFactory,
            KafkaTemplate<String, AuditEvent> kafkaTemplate) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, AuditEvent>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(auditErrorHandler(kafkaTemplate));
        return factory;
    }

    /**
     * Error handler: retry 3 times with 1s delay, then publish to DLQ.
     */
    private CommonErrorHandler auditErrorHandler(KafkaTemplate<String, AuditEvent> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(auditDlqTopic, 0));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3));
    }
}
