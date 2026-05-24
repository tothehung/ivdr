package com.ivdr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executors;

/**
 * IVDR Document Management System — Main Entry Point
 *
 * <p>Key architecture highlights:
 * <ul>
 *   <li>Java 21 Virtual Threads (Project Loom) — high-concurrency with blocking-style code</li>
 *   <li>Multi-tenant PostgreSQL with Row-Level Security</li>
 *   <li>Kafka event-driven audit pipeline</li>
 *   <li>WebSocket STOMP for real-time presence</li>
 *   <li>Redis for rate limiting and session cache</li>
 * </ul>
 */
@SpringBootApplication
@EnableCaching
@EnableKafka
@EnableAsync
@EnableScheduling
public class IvdrApplication {

    public static void main(String[] args) {
        // Configure virtual threads for the entire Spring Boot application
        // This allows the app to handle thousands of concurrent connections
        // without creating OS threads for each request
        System.setProperty("spring.threads.virtual.enabled", "true");
        SpringApplication.run(IvdrApplication.class, args);
    }
}
