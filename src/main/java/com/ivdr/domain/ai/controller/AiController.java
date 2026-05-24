package com.ivdr.domain.ai.controller;

import com.ivdr.common.response.ApiResponse;
import com.ivdr.domain.ai.service.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.ivdr.security.UserPrincipal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * REST controller that exposes AI-powered features of the IVDR platform.
 *
 * <p>All endpoints require an authenticated session.  Role-specific access is
 * enforced where noted with {@link PreAuthorize}.
 *
 * <p>Base path: {@code /ai}
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /ai/summarize/{documentId}}       — trigger or return a document summary</li>
 *   <li>{@code POST /ai/explain-anomaly/{auditLogId}} — explain an anomalous audit event</li>
 *   <li>{@code GET  /ai/recommendations}              — recommend documents for a query</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AiController {

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final AiService aiService;

    // -------------------------------------------------------------------------
    // Timeout for @Async calls awaited at the REST layer (seconds)
    // -------------------------------------------------------------------------
    private static final long ASYNC_WAIT_SECONDS = 60L;

    // =========================================================================
    // POST /ai/summarize/{documentId}
    // =========================================================================

    /**
     * Triggers an AI-generated summary for the specified document and returns the
     * result synchronously (waiting up to {@value ASYNC_WAIT_SECONDS} seconds).
     *
     * <p>In a production scenario this would:
     * <ol>
     *   <li>Check whether a cached summary already exists (e.g. in a
     *       {@code document_summaries} table).</li>
     *   <li>If not, fetch the document content, call {@link AiService#summarizeDocument},
     *       persist and return the result.</li>
     * </ol>
     *
     * <p>For this implementation the document metadata is supplied via request body
     * to keep the controller self-contained.
     *
     * @param documentId  UUID of the document to summarise
     * @param body        map containing optional overrides: {@code documentName},
     *                    {@code contentType}, {@code fileSizeBytes}, {@code textContent}
     * @param principal   the currently authenticated user
     * @return 200 OK with the summary string, or an error message on timeout
     */
    @PostMapping("/summarize/{documentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public ResponseEntity<ApiResponse<String>> summarizeDocument(
            @PathVariable UUID documentId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("AI summarize requested for document {} by user {}", documentId, principal.userId());

        // Extract fields from request body (use sensible defaults if not provided)
        String documentName  = getOrDefault(body, "documentName",  documentId + ".pdf");
        String contentType   = getOrDefault(body, "contentType",   "application/pdf");
        long   fileSizeBytes = getLongOrDefault(body, "fileSizeBytes", 0L);
        String textContent   = getOrDefault(body, "textContent",   "");

        CompletableFuture<String> future = aiService.summarizeDocument(
                documentName, contentType, fileSizeBytes, textContent);

        String summary = awaitAsync(future, "summarize-" + documentId);
        return ResponseEntity.ok(ApiResponse.ok("Document summary generated", summary));
    }

    // =========================================================================
    // POST /ai/explain-anomaly/{auditLogId}
    // =========================================================================

    /**
     * Returns an AI-generated explanation of why the specified audit log entry is
     * considered anomalous.
     *
     * <p>Only ADMIN and MANAGER roles can access anomaly explanations, as they
     * contain potentially sensitive security context.
     *
     * @param auditLogId UUID of the audit log entry to explain
     * @param body       map with {@code eventType}, {@code userId}, and {@code metadata}
     * @param principal  the currently authenticated user
     * @return 200 OK with the explanation text
     */
    @PostMapping("/explain-anomaly/{auditLogId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<String>> explainAnomaly(
            @PathVariable UUID auditLogId,
            @RequestBody(required = false) Map<String, Object> body,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("AI anomaly explanation requested for auditLog {} by user {}", auditLogId, principal.userId());

        String eventType = getOrDefault(body, "eventType", "UNKNOWN_EVENT");
        String userId    = getOrDefault(body, "userId", "unknown");

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = body != null && body.get("metadata") instanceof Map<?,?>
                ? (Map<String, Object>) body.get("metadata")
                : Map.of("auditLogId", auditLogId.toString());

        String explanation = aiService.explainAnomaly(eventType, userId, metadata);
        return ResponseEntity.ok(ApiResponse.ok("Anomaly explanation generated", explanation));
    }

    // =========================================================================
    // GET /ai/recommendations
    // =========================================================================

    /**
     * Returns AI-recommended documents from the workspace that are relevant to
     * the caller's query.
     *
     * <p>The {@code documents} query parameter accepts a comma-separated list of
     * document names available in the workspace.  In production this list would
     * be fetched from the document repository, but is accepted as a request
     * parameter here for flexibility.
     *
     * @param query     the user's natural-language search query (required)
     * @param documents comma-separated list of document names to consider
     * @param principal the currently authenticated user
     * @return 200 OK with the recommendation string (typically a JSON array of names)
     */
    @GetMapping("/recommendations")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public ResponseEntity<ApiResponse<String>> getRecommendations(
            @RequestParam String query,
            @RequestParam(defaultValue = "") List<String> documents,
            @AuthenticationPrincipal UserPrincipal principal) {

        log.info("AI recommendations requested by user {} for query '{}'", principal.userId(), query);

        String recommendation = aiService.recommendDocuments(documents, query);
        return ResponseEntity.ok(ApiResponse.ok("Document recommendations generated", recommendation));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Waits for an {@link CompletableFuture} result, applying the controller's
     * async timeout.  Returns a fallback message if the future does not complete
     * in time or throws.
     */
    private String awaitAsync(CompletableFuture<String> future, String label) {
        try {
            return future.get(ASYNC_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            log.warn("Async AI task '{}' timed out after {}s", label, ASYNC_WAIT_SECONDS);
            future.cancel(true);
            return "AI analysis timed out. Please try again later.";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Async AI task '{}' was interrupted", label);
            return "AI analysis was interrupted. Please try again later.";
        } catch (ExecutionException ex) {
            log.error("Async AI task '{}' failed: {}", label, ex.getCause().getMessage(), ex);
            return "AI analysis encountered an error. Please try again later.";
        }
    }

    private String getOrDefault(Map<String, Object> body, String key, String def) {
        if (body == null) return def;
        Object val = body.get(key);
        return val != null ? val.toString() : def;
    }

    private long getLongOrDefault(Map<String, Object> body, String key, long def) {
        if (body == null) return def;
        Object val = body.get(key);
        if (val == null) return def;
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
