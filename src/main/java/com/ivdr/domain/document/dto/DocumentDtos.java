package com.ivdr.domain.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Container class holding all Document-domain Data Transfer Object (DTO) records.
 *
 * <p>Using a single outer class groups related records together, avoids package
 * proliferation, and allows clean wildcard-style imports inside the service and
 * controller layers:
 * <pre>{@code
 *   import com.ivdr.domain.document.dto.DocumentDtos.*;
 * }</pre>
 *
 * <p>All records are Java 21 {@code record}s — immutable by design, with
 * auto-generated constructors, accessors, {@code equals}, {@code hashCode},
 * and {@code toString}.
 */
public final class DocumentDtos {

    /** Utility class — never instantiated. */
    private DocumentDtos() {}

    // =========================================================================
    // Inbound (request) records
    // =========================================================================

    /**
     * Payload that accompanies a multipart document upload request.
     *
     * <p>The actual file binary is handled by {@code MultipartFile} in the
     * controller; this record carries only the descriptive metadata supplied
     * as form fields.
     *
     * @param name        human-readable document name — must not be blank
     * @param description optional free-text description (may be {@code null})
     * @param tags        optional array of tag strings for categorisation;
     *                    {@code null} or empty array means no tags
     */
    public record UploadRequest(
            @NotBlank(message = "Document name must not be blank")
            String name,

            String description,

            String[] tags
    ) {}

    /**
     * Query parameters for document search and pagination.
     *
     * <p>Validated at the controller boundary before being forwarded to the
     * service layer. Both {@code page} and {@code size} default to zero/twenty
     * when the caller omits them; the service clamps {@code size} to a
     * configurable maximum.
     *
     * @param query  optional free-text search term (searches the document name)
     * @param status optional lifecycle status filter (e.g. {@code "ACTIVE"});
     *               defaults to {@code "ACTIVE"} in the service when {@code null}
     * @param page   zero-based page number
     * @param size   number of items per page (must be positive)
     */
    public record DocumentSearchRequest(
            String query,
            String status,

            @PositiveOrZero(message = "Page index must be 0 or greater")
            int page,

            @Positive(message = "Page size must be greater than 0")
            int size
    ) {}

    // =========================================================================
    // Outbound (response) records
    // =========================================================================

    /**
     * Full document metadata returned to the caller after a successful upload
     * or when listing documents.
     *
     * <p>Deliberately excludes storage-internal fields such as the S3
     * {@code fileKey} and the SHA-256 checksum — those are implementation
     * details not suitable for the API surface.
     *
     * @param id           document UUID
     * @param workspaceId  UUID of the owning workspace
     * @param name         human-readable document name
     * @param description  optional free-text description
     * @param contentType  MIME type of the stored file
     * @param fileSizeBytes raw file size in bytes
     * @param version      document revision number (1-based)
     * @param status       current lifecycle status
     * @param tags         array of tags associated with the document
     * @param aiSummary    AI-generated summary; may be {@code null} if async job
     *                     has not completed yet
     * @param uploadedBy   UUID of the user who uploaded the document
     * @param createdAt    timestamp of the initial upload
     */
    public record DocumentResponse(
            UUID id,
            UUID workspaceId,
            String name,
            String description,
            String contentType,
            long fileSizeBytes,
            int version,
            String status,
            String[] tags,
            String aiSummary,
            UUID uploadedBy,
            LocalDateTime createdAt
    ) {}

    /**
     * Response carrying a short-lived pre-signed S3 download URL.
     *
     * <p>Clients should treat this URL as opaque — its format may change between
     * deployments. The URL must be consumed before {@code expiresInSeconds}
     * seconds elapse from the moment it was issued.
     *
     * @param presignedUrl    the pre-signed HTTPS URL for direct S3 download
     * @param expiresInSeconds number of seconds until the URL becomes invalid
     */
    public record DownloadUrlResponse(
            @NotNull String presignedUrl,
            long expiresInSeconds
    ) {}
}
