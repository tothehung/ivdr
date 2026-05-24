package com.ivdr.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * Generic API response wrapper used across all IVDR endpoints.
 *
 * <p>Implemented as a Java 21 Record for immutability and conciseness.
 * {@code @JsonInclude(NON_NULL)} ensures that null fields (e.g. {@code data}
 * on error responses) are omitted from the serialised JSON payload.</p>
 *
 * <p>Usage examples:</p>
 * <pre>{@code
 *   return ResponseEntity.ok(ApiResponse.ok(myData));
 *   return ResponseEntity.ok(ApiResponse.ok("Record created", myData));
 *   return ResponseEntity.badRequest().body(ApiResponse.error("Validation failed"));
 * }</pre>
 *
 * @param <T> the type of the payload carried in {@code data}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        LocalDateTime timestamp
) {

    // -----------------------------------------------------------------------
    // Static factory methods
    // -----------------------------------------------------------------------

    /**
     * Creates a successful response with default message and a data payload.
     *
     * @param data the response payload
     * @param <T>  the payload type
     * @return a successful {@link ApiResponse}
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "Success", data, LocalDateTime.now());
    }

    /**
     * Creates a successful response with a custom message and a data payload.
     *
     * @param message a human-readable success message
     * @param data    the response payload
     * @param <T>     the payload type
     * @return a successful {@link ApiResponse}
     */
    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data, LocalDateTime.now());
    }

    /**
     * Creates an error response with no data payload.
     *
     * @param message a human-readable error message
     * @param <T>     the payload type (will be {@code null})
     * @return an error {@link ApiResponse}
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null, LocalDateTime.now());
    }
}
