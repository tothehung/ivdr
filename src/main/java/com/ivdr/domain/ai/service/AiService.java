package com.ivdr.domain.ai.service;

import com.ivdr.domain.document.entity.Document;
import com.ivdr.domain.document.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Concrete service for AI-powered document processing features.
 *
 * Supports OpenAI, Anthropic, or local mock fallbacks when API keys are not present.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final DocumentRepository documentRepository;

    @Value("${app.ai.provider:mock}")
    private String aiProvider;

    @Value("${app.ai.api-key:}")
    private String apiKey;

    @Value("${app.ai.anthropic.base-url:https://api.anthropic.com}")
    private String anthropicBaseUrl;

    @Value("${app.ai.anthropic.model:claude-3-5-haiku-20241022}")
    private String anthropicModel;

    @Value("${app.ai.openai.base-url:https://api.openai.com/v1}")
    private String openaiBaseUrl;

    @Value("${app.ai.openai.model:gpt-4o-mini}")
    private String openaiModel;

    @Value("${app.ai.timeout-seconds:30}")
    private int timeoutSeconds;

    /**
     * Asynchronously generates a natural-language summary of a document and
     * persists it back to the database.
     */
    @Async
    public void summariseDocument(UUID documentId, byte[] fileBytes, String contentType) {
        try {
            log.debug("Async summarizing documentId={}", documentId);
            Document doc = documentRepository.findById(documentId).orElse(null);
            if (doc == null) {
                log.warn("Document not found for id={}", documentId);
                return;
            }

            // Simple text extraction placeholder: convert bytes to string
            String textContent = new String(fileBytes, StandardCharsets.UTF_8);
            if (textContent.length() > 5000) {
                textContent = textContent.substring(0, 5000);
            }

            String summary = callAiApi("Summarize this document content: \n" + textContent);
            doc.setAiSummary(summary);
            documentRepository.save(doc);
            log.info("Successfully generated and saved AI summary for documentId={}", documentId);
        } catch (Exception e) {
            log.error("Failed to generate AI summary for documentId={}: {}", documentId, e.getMessage(), e);
        }
    }

    /**
     * REST endpoint wrapper for document summarisation.
     */
    @Async
    public CompletableFuture<String> summarizeDocument(String documentName, String contentType, long fileSizeBytes, String textContent) {
        String prompt = String.format("Summarize document '%s' (%s, %d bytes):\n%s", documentName, contentType, fileSizeBytes, textContent);
        try {
            String summary = callAiApi(prompt);
            return CompletableFuture.completedFuture(summary);
        } catch (Exception e) {
            log.error("Failed to summarize document name={}: {}", documentName, e.getMessage());
            return CompletableFuture.completedFuture("Failed to generate summary: " + e.getMessage());
        }
    }

    /**
     * Explains why a specific action is anomalous.
     */
    public String explainAnomaly(String eventType, String userId, Map<String, Object> metadata) {
        String prompt = String.format("Explain why this event is anomalous: eventType=%s, userId=%s, metadata=%s", eventType, userId, metadata.toString());
        try {
            return callAiApi(prompt);
        } catch (Exception e) {
            log.error("AI Anomaly explanation failed, returning fallback: {}", e.getMessage());
            return "The activity was flagged because it exceeded the typical baseline behavior for event " + eventType + " associated with user " + userId + ".";
        }
    }

    /**
     * Recommends relevant documents.
     */
    public String recommendDocuments(List<String> documentNames, String userQuery) {
        String prompt = String.format("Recommend relevant documents from this list: %s for query: '%s'", String.join(", ", documentNames), userQuery);
        try {
            return callAiApi(prompt);
        } catch (Exception e) {
            log.error("AI Recommendation failed, returning fallback: {}", e.getMessage());
            return "Based on your search query, we suggest checking the most active compliance documents in this workspace.";
        }
    }

    private String callAiApi(String prompt) {
        if ("mock".equalsIgnoreCase(aiProvider) || apiKey == null || apiKey.isBlank()) {
            if (prompt.contains("Summarize") || prompt.contains("summarise") || prompt.contains("document")) {
                return "This document contains standard compliance records and transaction logs for Multi-tenant operations in the IVDR Document Management System. The contents verify key security compliance metrics, tenant configurations, and trace activities.";
            } else if (prompt.contains("explain") || prompt.contains("Anomaly")) {
                return "The event was flagged because it exceeded the established behavior baseline. A high rate of activity or authentication failures was detected, which is characteristic of anomalous API consumption, data scraping, or brute-force attempts.";
            } else if (prompt.contains("Recommend") || prompt.contains("recommend")) {
                return "Based on your search query, we recommend reviewing: 'security_policy_v2.docx' and 'compliance_report_2026.pdf'.";
            }
            return "Mock AI response for: " + (prompt.length() > 50 ? prompt.substring(0, 50) + "..." : prompt);
        }

        try {
            WebClient webClient = WebClient.builder().build();
            if ("openai".equalsIgnoreCase(aiProvider)) {
                Map<String, Object> request = Map.of(
                    "model", openaiModel,
                    "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                    ),
                    "max_tokens", 4096
                );
                Map<?, ?> response = webClient.post()
                    .uri(openaiBaseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(java.time.Duration.ofSeconds(timeoutSeconds));

                if (response != null && response.get("choices") instanceof List<?> choices) {
                    if (!choices.isEmpty() && choices.get(0) instanceof Map<?, ?> firstChoice) {
                        if (firstChoice.get("message") instanceof Map<?, ?> message) {
                            return message.get("content").toString();
                        }
                    }
                }
            } else if ("anthropic".equalsIgnoreCase(aiProvider)) {
                Map<String, Object> request = Map.of(
                    "model", anthropicModel,
                    "max_tokens", 4096,
                    "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                    )
                );
                Map<?, ?> response = webClient.post()
                    .uri(anthropicBaseUrl + "/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(java.time.Duration.ofSeconds(timeoutSeconds));

                if (response != null && response.get("content") instanceof List<?> contentList) {
                    if (!contentList.isEmpty() && contentList.get(0) instanceof Map<?, ?> firstContent) {
                        return firstContent.get("text").toString();
                    }
                }
            }
        } catch (Exception ex) {
            log.error("AI API call failed, falling back to mock: {}", ex.getMessage());
        }

        return "This is a fallback summary of the document, as the remote AI service call failed or timed out.";
    }
}
