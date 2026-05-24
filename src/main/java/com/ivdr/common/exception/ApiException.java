package com.ivdr.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Domain-level runtime exception that carries an {@link HttpStatus}, a
 * human-readable message, and an application-specific {@code errorCode}.
 *
 * <p>Throw this anywhere in the service or controller layer; the
 * {@link GlobalExceptionHandler} will translate it into the appropriate
 * HTTP response automatically.</p>
 *
 * <p>Usage examples:</p>
 * <pre>{@code
 *   throw ApiException.notFound("Device not found with id: " + id);
 *   throw ApiException.conflict("A record with this serial number already exists");
 *   throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot process entity", "IVDR-422");
 * }</pre>
 */
public class ApiException extends RuntimeException {

    /** HTTP status code to return to the caller. */
    private final HttpStatus status;

    /** Application-level error code used by clients to handle errors programmatically. */
    private final String errorCode;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Full constructor.
     *
     * @param status    the HTTP status to send in the response
     * @param message   a human-readable description of the error
     * @param errorCode an application-specific error code string (e.g. {@code "IVDR-404"})
     */
    public ApiException(HttpStatus status, String message, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Returns the HTTP status associated with this exception. */
    public HttpStatus getStatus() {
        return status;
    }

    /** Returns the application-level error code. */
    public String getErrorCode() {
        return errorCode;
    }

    // -----------------------------------------------------------------------
    // Static factory methods
    // -----------------------------------------------------------------------

    /**
     * Creates a {@code 400 Bad Request} exception.
     *
     * @param message a description of what was wrong with the request
     * @return a new {@link ApiException}
     */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, "IVDR-400");
    }

    /**
     * Creates a {@code 401 Unauthorized} exception.
     *
     * @param message a description of the authentication failure
     * @return a new {@link ApiException}
     */
    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message, "IVDR-401");
    }

    /**
     * Creates a {@code 403 Forbidden} exception.
     *
     * @param message a description of the authorisation failure
     * @return a new {@link ApiException}
     */
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message, "IVDR-403");
    }

    /**
     * Creates a {@code 404 Not Found} exception.
     *
     * @param message a description of what was not found
     * @return a new {@link ApiException}
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message, "IVDR-404");
    }

    /**
     * Creates a {@code 409 Conflict} exception.
     *
     * @param message a description of the conflict
     * @return a new {@link ApiException}
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message, "IVDR-409");
    }

    /**
     * Creates a {@code 500 Internal Server Error} exception.
     *
     * @param message a description of the internal error (not exposed to callers)
     * @return a new {@link ApiException}
     */
    public static ApiException internalError(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, message, "IVDR-500");
    }
}
