package com.ivdr.domain.document.controller;

import com.ivdr.domain.document.dto.DocumentDtos.*;
import com.ivdr.domain.document.service.DocumentService;
import com.ivdr.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * REST controller exposing the Document domain API.
 *
 * <p>All endpoints are scoped under a specific workspace:
 * <pre>  /workspaces/{workspaceId}/documents</pre>
 *
 * <p>The {@code workspaceId} path variable is passed through to the service
 * layer for every request, ensuring that cross-workspace data access is
 * structurally impossible at the routing level.
 *
 * <p>Authentication is enforced by the Spring Security filter chain; the
 * resolved {@link UserPrincipal} is injected via
 * {@link AuthenticationPrincipal}.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /workspaces/{workspaceId}/documents/upload} — upload a new document (multipart)</li>
 *   <li>{@code GET  /workspaces/{workspaceId}/documents} — paginated document listing</li>
 *   <li>{@code GET  /workspaces/{workspaceId}/documents/{documentId}/download-url} — get pre-signed URL</li>
 *   <li>{@code DELETE /workspaces/{workspaceId}/documents/{documentId}} — soft-delete a document</li>
 * </ul>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/workspaces/{workspaceId}/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Document upload, listing, download and management")
public class DocumentController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE     = 100;

    private final DocumentService documentService;

    // =========================================================================
    // POST /upload  — Upload a document
    // =========================================================================

    /**
     * Uploads a new document to the specified workspace.
     *
     * <p>The request must be sent as {@code multipart/form-data} with:
     * <ul>
     *   <li>{@code file} — the binary file part</li>
     *   <li>{@code name} — required; human-readable document name</li>
     *   <li>{@code description} — optional; free-text description</li>
     *   <li>{@code tags} — optional; comma-separated or multi-value tags</li>
     * </ul>
     *
     * @param workspaceId the target workspace UUID (path variable)
     * @param file        the uploaded file binary
     * @param name        document name (form field, required)
     * @param description document description (form field, optional)
     * @param tags        document tags (form field array, optional)
     * @param principal   the authenticated user extracted from the JWT
     * @return {@code 201 Created} with the persisted document metadata
     */
    @Operation(
            summary = "Upload a document",
            description = "Accepts a multipart file upload and stores it in S3. " +
                          "Returns the persisted document metadata. " +
                          "Rate limit: 20 uploads per minute per user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Document uploaded successfully",
                    content = @Content(schema = @Schema(implementation = DocumentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request — missing file or name"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "429", description = "Upload rate limit exceeded")
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadDocument(
            @Parameter(description = "UUID of the target workspace", required = true)
            @PathVariable UUID workspaceId,

            @Parameter(description = "Optional folder ID parameter")
            @RequestParam(value = "folderId", required = false) UUID folderId,

            @Parameter(description = "The file to upload", required = true)
            @RequestPart("file") MultipartFile file,

            @Parameter(description = "Human-readable document name", required = true)
            @RequestPart("name") String name,

            @Parameter(description = "Optional free-text description")
            @RequestPart(value = "description", required = false) String description,

            @Parameter(description = "Optional array of tag strings")
            @RequestPart(value = "tags", required = false) String[] tags,

            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Upload request received — workspaceId={} folderId={} filename={} userId={}",
                workspaceId, folderId, file.getOriginalFilename(), principal.userId());

        UploadRequest req = new UploadRequest(name, description, tags);
        DocumentResponse response = documentService.uploadDocument(workspaceId, folderId, file, req, principal);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/link")
    public ResponseEntity<DocumentResponse> uploadLink(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID folderId,
            @Valid @RequestBody LinkUploadRequest req,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Link upload request received — workspaceId={} folderId={} name={} url={}",
                workspaceId, folderId, req.name(), req.url());

        DocumentResponse response = documentService.uploadLink(workspaceId, folderId, req, principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // =========================================================================
    // GET /  — List documents (paginated)
    // =========================================================================

    /**
     * Returns a paginated list of documents in the given workspace.
     *
     * <p>Supports optional filtering by lifecycle {@code status} and sorting
     * via standard Spring Data Pageable conventions.
     *
     * @param workspaceId the workspace UUID (path variable)
     * @param status      optional lifecycle status filter (default: {@code ACTIVE})
     * @param page        zero-based page index (default: 0)
     * @param size        page size, 1–100 (default: 20)
     * @param sortBy      field to sort by (default: {@code createdAt})
     * @param sortDir     sort direction: {@code asc} or {@code desc} (default: {@code desc})
     * @param principal   the authenticated user
     * @return {@code 200 OK} with a {@link Page} of {@link DocumentResponse} records
     */
    @Operation(
            summary = "List documents",
            description = "Returns a paginated list of documents for the specified workspace. " +
                          "Filter by status (ACTIVE, ARCHIVED, PROCESSING). " +
                          "Default status filter is ACTIVE."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Documents listed successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping
    public ResponseEntity<Page<DocumentResponse>> listDocuments(
            @Parameter(description = "UUID of the workspace", required = true)
            @PathVariable UUID workspaceId,

            @Parameter(description = "Optional folder ID parameter")
            @RequestParam(required = false) UUID folderId,

            @Parameter(description = "Lifecycle status filter. Defaults to ACTIVE.")
            @RequestParam(required = false) String status,

            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size (1–100)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,

            @Parameter(description = "Field to sort by")
            @RequestParam(defaultValue = "createdAt") String sortBy,

            @Parameter(description = "Sort direction: asc or desc")
            @RequestParam(defaultValue = "desc") String sortDir,

            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Sort sort = "asc".equalsIgnoreCase(sortDir)
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);

        Page<DocumentResponse> result = documentService.listDocuments(
                workspaceId, status, folderId, pageable, principal);

        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // GET /{documentId}/download-url  — Generate pre-signed download URL
    // =========================================================================

    /**
     * Generates and returns a short-lived pre-signed S3 URL for downloading
     * the specified document.
     *
     * <p>The URL expires after the duration configured in
     * {@code ivdr.storage.presigned-url-expiry-minutes} (default: 15 min).
     * An audit event ({@code DOCUMENT_DOWNLOADED}) is recorded at this point.
     *
     * @param workspaceId the workspace that owns the document (path variable, for future authorisation)
     * @param documentId  the UUID of the document to download
     * @param principal   the authenticated user
     * @return {@code 200 OK} with the pre-signed URL and its expiry duration in seconds
     */
    @Operation(
            summary = "Get document download URL",
            description = "Generates a pre-signed S3 URL that grants temporary, unauthenticated " +
                          "read access to the document file. " +
                          "Rate limit: 100 downloads per minute per user."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pre-signed URL generated successfully",
                    content = @Content(schema = @Schema(implementation = DownloadUrlResponse.class))),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Document not found"),
            @ApiResponse(responseCode = "429", description = "Download rate limit exceeded")
    })
    @GetMapping("/{documentId}/download-url")
    public ResponseEntity<DownloadUrlResponse> getDownloadUrl(
            @Parameter(description = "UUID of the workspace", required = true)
            @PathVariable UUID workspaceId,

            @Parameter(description = "UUID of the document to download", required = true)
            @PathVariable UUID documentId,

            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Download URL requested — documentId={} userId={}", documentId, principal.userId());

        DownloadUrlResponse response = documentService.downloadDocument(documentId, principal);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // GET /{documentId}/preview-url  — Get inline preview URL (no download audit)
    // =========================================================================

    /**
     * Returns a pre-signed S3 URL for previewing the document inline in the browser.
     * Unlike download-url, this does NOT record a DOCUMENT_DOWNLOADED audit event.
     *
     * @param workspaceId UUID of the workspace
     * @param documentId  UUID of the document to preview
     * @param principal   the authenticated user
     * @return 200 OK with the pre-signed preview URL
     */
    @GetMapping("/{documentId}/preview-url")
    public ResponseEntity<DownloadUrlResponse> getPreviewUrl(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Preview URL requested — documentId={} userId={}", documentId, principal.userId());

        DownloadUrlResponse response = documentService.getPreviewUrl(documentId, principal);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // DELETE /{documentId}  — Soft-delete a document
    // =========================================================================

    /**
     * Soft-deletes the specified document by setting its status to {@code DELETED}.
     *
     * <p>The physical S3 object is retained for a configurable grace period
     * and is removed by a scheduled background job. This allows recovery in
     * case of accidental deletion.
     *
     * @param workspaceId the workspace that owns the document
     * @param documentId  the UUID of the document to delete
     * @param principal   the authenticated user
     * @return {@code 204 No Content} on success
     */
    @Operation(
            summary = "Delete a document",
            description = "Soft-deletes the document (sets status to DELETED). " +
                          "The S3 object is removed by a background cleanup job after a grace period. " +
                          "Fires an audit event on deletion."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Document deleted successfully"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "404", description = "Document not found or already deleted")
    })
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @Parameter(description = "UUID of the workspace", required = true)
            @PathVariable UUID workspaceId,

            @Parameter(description = "UUID of the document to delete", required = true)
            @PathVariable UUID documentId,

            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Delete request received — documentId={} workspaceId={} userId={}",
                documentId, workspaceId, principal.userId());

        documentService.deleteDocument(documentId, workspaceId, principal);
        return ResponseEntity.noContent().build();
    }

    /**
     * Updates metadata (name, description, tags) for an existing document.
     *
     * <p>Only workspace EDITOR or OWNER can update document metadata.
     *
     * @param workspaceId the workspace UUID
     * @param documentId  the document UUID
     * @param req         metadata update request payload
     * @param principal   the authenticated caller
     * @return 200 OK with the updated document details
     */
    @PutMapping("/{documentId}")
    public ResponseEntity<DocumentResponse> updateDocument(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @Valid @RequestBody UpdateMetadataRequest req,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Update metadata request received — documentId={} workspaceId={} userId={}",
                documentId, workspaceId, principal.userId());

        DocumentResponse response = documentService.updateDocument(workspaceId, documentId, req, principal);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{documentId}/content")
    public ResponseEntity<DocumentResponse> updateDocumentContent(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @Valid @RequestBody UpdateContentRequest req,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Update content request received — documentId={} workspaceId={} userId={}",
                documentId, workspaceId, principal.userId());

        DocumentResponse response = documentService.updateDocumentContent(workspaceId, documentId, req, principal);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{documentId}/password")
    public ResponseEntity<DocumentResponse> setDocumentPassword(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @RequestBody SetPasswordRequest req,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Set password request received — documentId={} workspaceId={} userId={}",
                documentId, workspaceId, principal.userId());

        DocumentResponse response = documentService.setDocumentPassword(workspaceId, documentId, req, principal);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{documentId}/verify-password")
    public ResponseEntity<DownloadUrlResponse> verifyDocumentPassword(
            @PathVariable UUID workspaceId,
            @PathVariable UUID documentId,
            @RequestParam(value = "type", defaultValue = "preview") String type,
            @Valid @RequestBody VerifyPasswordRequest req,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Verify password request received — documentId={} workspaceId={} type={} userId={}",
                documentId, workspaceId, type, principal.userId());

        DownloadUrlResponse response = documentService.verifyDocumentPasswordAndGetUrl(workspaceId, documentId, req, type, principal);
        return ResponseEntity.ok(response);
    }
}
