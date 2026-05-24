package com.ivdr.common.exception;

import com.ivdr.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralised exception handler for all IVDR REST controllers.
 *
 * <p>Every unhandled exception bubbling up from the controller layer is caught
 * here and translated into a structured {@link ApiResponse} with the
 * appropriate HTTP status code.  Sensitive internal details (stack traces,
 * database messages, etc.) are never forwarded to the client.</p>
 *
 * <p>Handler precedence (most-specific first):</p>
 * <ol>
 *   <li>{@link ApiException}                    – domain exceptions with an explicit status</li>
 *   <li>{@link MethodArgumentNotValidException} – Bean Validation failures (400)</li>
 *   <li>{@link AccessDeniedException}           – Spring Security authorisation failure (403)</li>
 *   <li>{@link AuthenticationException}         – Spring Security authentication failure (401)</li>
 *   <li>{@link Exception}                       – catch-all (500), details logged server-side</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // -----------------------------------------------------------------------
    // 1. ApiException — domain-driven errors with an explicit HTTP status
    // -----------------------------------------------------------------------

    /**
     * Handles {@link ApiException} thrown anywhere in the service or controller
     * layer.  The HTTP status, message, and errorCode are forwarded directly to
     * the caller because they are intentionally crafted for client consumption.
     *
     * @param ex the caught exception
     * @return a response whose HTTP status mirrors {@code ex.getStatus()}
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException ex) {
        log.warn("ApiException [{}] {}: {}", ex.getStatus().value(), ex.getErrorCode(), ex.getMessage());
        ApiResponse<Void> body = ApiResponse.error(ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    // -----------------------------------------------------------------------
    // 2. MethodArgumentNotValidException — Bean Validation failures
    // -----------------------------------------------------------------------

    /**
     * Handles validation failures produced by {@code @Valid} / {@code @Validated}
     * on controller method parameters.
     *
     * <p>The response body includes a {@code data} map where each key is a
     * field name and each value is the corresponding constraint violation message,
     * making it straightforward for clients to highlight the offending form fields.</p>
     *
     * @param ex the caught validation exception
     * @return {@code 400 Bad Request} with a map of field → error message
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            // Keep only the first error per field to avoid overwhelming the response
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }

        log.debug("Validation failed on {} field(s): {}", fieldErrors.size(), fieldErrors.keySet());

        ApiResponse<Map<String, String>> body = new ApiResponse<>(
                false,
                "Validation failed. Please correct the highlighted fields.",
                fieldErrors,
                java.time.LocalDateTime.now()
        );
        return ResponseEntity.badRequest().body(body);
    }

    // -----------------------------------------------------------------------
    // 3. AccessDeniedException — Spring Security authorisation failure
    // -----------------------------------------------------------------------

    /**
     * Handles {@link AccessDeniedException} raised by Spring Security when an
     * authenticated principal does not have the required authority/role.
     *
     * <p>We intentionally return a generic message to avoid leaking information
     * about the existence of resources or the permission model.</p>
     *
     * @param ex the caught exception (message is intentionally suppressed)
     * @return {@code 403 Forbidden}
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("You do not have permission to perform this action."));
    }

    // -----------------------------------------------------------------------
    // 4. AuthenticationException — Spring Security authentication failure
    // -----------------------------------------------------------------------

    /**
     * Handles {@link AuthenticationException} raised when a request cannot be
     * authenticated (e.g. missing or expired JWT, bad credentials).
     *
     * @param ex the caught exception
     * @return {@code 401 Unauthorized}
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        log.warn("Authentication failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication is required to access this resource."));
    }

    // -----------------------------------------------------------------------
    // 5. Generic catch-all — unexpected runtime exceptions
    // -----------------------------------------------------------------------

    /**
     * Catch-all handler for any {@link Exception} not matched by a more specific
     * handler above.
     *
     * <p><strong>Security note:</strong> the exception detail is intentionally
     * withheld from the response body; only a generic message is returned.
     * The full stack trace is logged at ERROR level for operator visibility.</p>
     *
     * @param ex the caught exception
     * @return {@code 500 Internal Server Error} with a generic error message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                        "An unexpected error occurred. Please contact support if the problem persists."));
    }
}
