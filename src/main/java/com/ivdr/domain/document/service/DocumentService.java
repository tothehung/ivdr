package com.ivdr.domain.document.service;

import com.ivdr.common.exception.ApiException;
import com.ivdr.domain.audit.event.AuditEventType;
import com.ivdr.domain.document.dto.DocumentDtos.*;
import com.ivdr.domain.document.entity.Document;
import com.ivdr.domain.document.repository.DocumentRepository;
import com.ivdr.domain.ai.service.AiService;
import com.ivdr.domain.analytics.service.AnalyticsService;
import com.ivdr.domain.workspace.entity.WorkspaceMember;
import com.ivdr.domain.workspace.repository.WorkspaceMemberRepository;
import com.ivdr.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;


/**
 * Core service for Document lifecycle management.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Enforcing per-user rate limits for uploads and downloads via
 *       {@link RateLimiterService}.</li>
 *   <li>Computing SHA-256 checksums for file integrity verification.</li>
 *   <li>Delegating binary storage to {@link StorageService}.</li>
 *   <li>Persisting and querying {@link Document} entities through
 *       {@link DocumentRepository}.</li>
 *   <li>Publishing audit events via Spring's {@link ApplicationEventPublisher}.</li>
 *   <li>Triggering AI summarisation asynchronously after upload via
 *       {@link AiService}.</li>
 * </ul>
 *
 * <h2>Rate limits</h2>
 * <ul>
 *   <li>Upload: 20 requests per minute per user</li>
 *   <li>Download: 100 requests per minute per user</li>
 * </ul>
 *
 * <h2>Transaction strategy</h2>
 * <p>All mutating public methods are annotated with {@link Transactional}.
 * Read-only methods use {@code readOnly = true} to allow the JPA provider to
 * skip dirty-checking and potentially route to a read replica.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    // -------------------------------------------------------------------------
    // Rate limit constants
    // -------------------------------------------------------------------------

    private static final int UPLOAD_RATE_LIMIT_MAX     = 20;
    private static final int UPLOAD_RATE_LIMIT_WINDOW  = 60; // seconds
    private static final int DOWNLOAD_RATE_LIMIT_MAX   = 100;
    private static final int DOWNLOAD_RATE_LIMIT_WINDOW = 60; // seconds

    // -------------------------------------------------------------------------
    // Injected dependencies
    // -------------------------------------------------------------------------

    private final DocumentRepository   documentRepository;
    private final StorageService        storageService;
    private final RateLimiterService    rateLimiterService;
    private final AiService             aiService;
    private final ApplicationEventPublisher eventPublisher;
    private final SimpMessagingTemplate messagingTemplate;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final AnalyticsService      analyticsService;
    private final PasswordEncoder       passwordEncoder;

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    /**
     * Handles the full document upload flow:
     * <ol>
     *   <li>Rate-limit check (20 uploads/min per user).</li>
     *   <li>SHA-256 checksum computation.</li>
     *   <li>Binary upload to S3 via {@link StorageService}.</li>
     *   <li>Persist {@link Document} entity with {@code status = ACTIVE}.</li>
     *   <li>Publish {@link AuditEventType#DOCUMENT_UPLOADED} event.</li>
     *   <li>Asynchronously trigger AI summarisation.</li>
     * </ol>
     *
     * @param workspaceId the target workspace
     * @param file        the multipart file payload from the HTTP request
     * @param req         supplementary metadata (name, description, tags)
     * @param principal   the authenticated user performing the upload
     * @return a {@link DocumentResponse} describing the newly created document
     * @throws ApiException (429) if the user has exceeded the upload rate limit
     * @throws ApiException (400) if the file is empty or cannot be read
     * @throws ApiException (500) if the S3 upload fails
     */
    @Transactional
    public DocumentResponse uploadDocument(UUID workspaceId,
                                           UUID folderId,
                                           MultipartFile file,
                                           UploadRequest req,
                                           UserPrincipal principal) {

        // 1 — Rate limit
        String rateLimitKey = RateLimiterService.buildKey("upload", principal.userId().toString());
        if (!rateLimiterService.isAllowed(rateLimitKey, UPLOAD_RATE_LIMIT_MAX, UPLOAD_RATE_LIMIT_WINDOW)) {
            log.warn("Upload rate limit exceeded — userId={} workspaceId={}", principal.userId(), workspaceId);
            publishAuditEvent(AuditEventType.RATE_LIMIT_EXCEEDED, principal, workspaceId, null);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Upload rate limit exceeded. Please wait before uploading again.",
                    "IVDR-429");
        }

        // 2 — Validate file
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Uploaded file must not be empty.");
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException ex) {
            log.error("Failed to read multipart file bytes — workspaceId={} error={}",
                    workspaceId, ex.getMessage(), ex);
            throw ApiException.badRequest("Could not read uploaded file: " + ex.getMessage());
        }

        // 3 — Compute SHA-256 checksum
        String checksum = computeSha256(fileBytes);

        // 4 — Upload to S3
        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "unknown";
        String contentType = file.getContentType() != null
                ? file.getContentType()
                : "application/octet-stream";

        String fileKey = storageService.uploadFile(
                principal.organizationId(),
                workspaceId,
                originalFilename,
                new java.io.ByteArrayInputStream(fileBytes),
                file.getSize(),
                contentType
        );

        // 5 — Persist entity
        Document document = Document.builder()
                .workspaceId(workspaceId)
                .organizationId(principal.organizationId())
                .name(req.name())
                .description(req.description())
                .fileKey(fileKey)
                .fileSizeBytes(file.getSize())
                .contentType(contentType)
                .version(1)
                .status("ACTIVE")
                .tags(req.tags())
                .checksumSha256(checksum)
                .uploadedBy(principal.userId())
                .folderId(folderId)
                .build();

        Document saved = documentRepository.save(document);
        log.info("Document persisted — id={} workspaceId={} name={} size={}",
                saved.getId(), workspaceId, saved.getName(), saved.getFileSizeBytes());

        // 6 — Audit event
        publishAuditEvent(AuditEventType.DOCUMENT_UPLOADED, principal, workspaceId, saved.getId());

        // 7 — Async AI summarisation (fire and forget)
        triggerAiSummaryAsync(saved.getId(), fileBytes, contentType);

        return toResponse(saved);
    }

    @Transactional
    public DocumentResponse uploadLink(UUID workspaceId,
                                       UUID folderId,
                                       LinkUploadRequest req,
                                       UserPrincipal principal) {
        // Enforce: OWNER or EDITOR can add links
        WorkspaceMember membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, principal.userId())
                .orElseThrow(() -> ApiException.forbidden("You are not a member of this workspace."));

        if (membership.getRole() == WorkspaceMember.MemberRole.VIEWER) {
            throw ApiException.forbidden("VIEWER role cannot add links. Only EDITOR or OWNER can add.");
        }

        Document document = Document.builder()
                .workspaceId(workspaceId)
                .organizationId(principal.organizationId())
                .name(req.name())
                .description(req.description())
                .fileKey(req.url()) // Store link in fileKey
                .fileSizeBytes(0)
                .contentType("link") // Mark type as link
                .version(1)
                .status("ACTIVE")
                .tags(req.tags())
                .checksumSha256("")
                .uploadedBy(principal.userId())
                .folderId(folderId)
                .build();

        Document saved = documentRepository.save(document);
        log.info("Link document persisted — id={} workspaceId={} name={} url={}",
                saved.getId(), workspaceId, saved.getName(), saved.getFileKey());

        publishAuditEvent(AuditEventType.DOCUMENT_UPLOADED, principal, workspaceId, saved.getId());

        return toResponse(saved);
    }

    // -------------------------------------------------------------------------
    // List
    // -------------------------------------------------------------------------

    /**
     * Returns a paginated list of documents in the specified workspace that
     * match the given status.
     *
     * @param workspaceId the workspace to list documents for
     * @param status      lifecycle status filter; defaults to {@code "ACTIVE"} when {@code null}
     * @param pageable    pagination and sorting parameters
     * @param principal   the authenticated caller (used for future authorisation checks)
     * @return a page of {@link DocumentResponse} records
     */
    @Transactional(readOnly = true)
    public Page<DocumentResponse> listDocuments(UUID workspaceId,
                                                 String status,
                                                 UUID folderId,
                                                 Pageable pageable,
                                                 UserPrincipal principal) {
        String resolvedStatus = (status != null && !status.isBlank()) ? status : "ACTIVE";
        log.debug("Listing documents — workspaceId={} folderId={} status={} page={} size={}",
                workspaceId, folderId, resolvedStatus, pageable.getPageNumber(), pageable.getPageSize());

        return documentRepository
                .findByWorkspaceIdAndStatusAndFolderId(workspaceId, resolvedStatus, folderId, pageable)
                .map(this::toResponse);
    }

    // -------------------------------------------------------------------------
    // Download
    // -------------------------------------------------------------------------

    /**
     * Generates a pre-signed S3 download URL for the given document.
     *
     * <p>Flow:
     * <ol>
     *   <li>Rate-limit check (100 downloads/min per user).</li>
     *   <li>Load and validate the document exists and is not deleted.</li>
     *   <li>Generate pre-signed URL via {@link StorageService}.</li>
     *   <li>Publish {@link AuditEventType#DOCUMENT_DOWNLOADED} event.</li>
     * </ol>
     *
     * @param documentId the UUID of the document to download
     * @param principal  the authenticated caller
     * @return a {@link DownloadUrlResponse} containing the pre-signed URL
     * @throws ApiException (429) if the download rate limit is exceeded
     * @throws ApiException (404) if the document does not exist or is deleted
     */
    @Transactional
    public DownloadUrlResponse downloadDocument(UUID documentId, UserPrincipal principal) {

        // 1 — Rate limit
        String rateLimitKey = RateLimiterService.buildKey("download", principal.userId().toString());
        if (!rateLimiterService.isAllowed(rateLimitKey, DOWNLOAD_RATE_LIMIT_MAX, DOWNLOAD_RATE_LIMIT_WINDOW)) {
            log.warn("Download rate limit exceeded — userId={} documentId={}", principal.userId(), documentId);
            publishAuditEvent(AuditEventType.RATE_LIMIT_EXCEEDED, principal, null, documentId);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Download rate limit exceeded. Please wait before downloading again.",
                    "IVDR-429");
        }

        // 2 — Load document (any workspace the user is authenticated into)
        Document document = documentRepository.findById(documentId)
                .filter(d -> !"DELETED".equals(d.getStatus()))
                .orElseThrow(() -> ApiException.notFound("Document not found: " + documentId));

        if (document.getPasswordHash() != null && !document.getPasswordHash().isEmpty()) {
            WorkspaceMember membership = workspaceMemberRepository
                    .findByWorkspaceIdAndUserId(document.getWorkspaceId(), principal.userId())
                    .orElseThrow(() -> ApiException.forbidden("You are not a member of this workspace."));
            if (membership.getRole() != WorkspaceMember.MemberRole.OWNER) {
                throw ApiException.forbidden("This document is password protected. Verification is required.");
            }
        }

        // 3 — Generate pre-signed URL with attachment Content-Disposition (forces browser download)
        int expiryMinutes = storageService.getDefaultPresignedUrlExpiryMinutes();
        String presignedUrl = storageService.generatePresignedDownloadUrl(
                document.getFileKey(), expiryMinutes, document.getName());

        // 4 — Audit event
        publishAuditEvent(AuditEventType.DOCUMENT_DOWNLOADED, principal,
                document.getWorkspaceId(), documentId);

        // 5 — Evict analytics cache so total downloads counter refreshes immediately
        try {
            analyticsService.evictWorkspaceStats(document.getWorkspaceId());
        } catch (Exception ex) {
            log.warn("Analytics cache eviction failed (non-critical): {}", ex.getMessage());
        }

        // Broadcast WebSocket download alert to the workspace
        try {
            messagingTemplate.convertAndSend("/topic/presence/" + document.getWorkspaceId(),
                    new com.ivdr.domain.presence.service.PresenceService.PresenceEvent(
                            "DOWNLOAD",
                            principal.userId(),
                            null,
                            documentId,
                            java.time.Instant.now()
                    )
            );
        } catch (Exception ex) {
            log.warn("Failed to broadcast document download event via WebSocket: {}", ex.getMessage());
        }

        log.info("Pre-signed download URL issued — documentId={} userId={} expiryMinutes={}",
                documentId, principal.userId(), expiryMinutes);

        return new DownloadUrlResponse(presignedUrl, (long) expiryMinutes * 60);
    }

    /**
     * Generates a pre-signed S3 preview URL (inline content-disposition, no forced download).
     * Used by the preview pane — does NOT publish a DOCUMENT_DOWNLOADED audit event.
     *
     * @param documentId the UUID of the document
     * @param principal  the authenticated caller
     * @return a {@link DownloadUrlResponse} with a preview URL
     */
    @Transactional(readOnly = true)
    public DownloadUrlResponse getPreviewUrl(UUID documentId, UserPrincipal principal) {
        Document document = documentRepository.findById(documentId)
                .filter(d -> !"DELETED".equals(d.getStatus()))
                .orElseThrow(() -> ApiException.notFound("Document not found: " + documentId));

        if (document.getPasswordHash() != null && !document.getPasswordHash().isEmpty()) {
            WorkspaceMember membership = workspaceMemberRepository
                    .findByWorkspaceIdAndUserId(document.getWorkspaceId(), principal.userId())
                    .orElseThrow(() -> ApiException.forbidden("You are not a member of this workspace."));
            if (membership.getRole() != WorkspaceMember.MemberRole.OWNER) {
                throw ApiException.forbidden("This document is password protected. Verification is required.");
            }
        }

        int expiryMinutes = storageService.getDefaultPresignedUrlExpiryMinutes();
        // No filename param = inline disposition (opens in browser)
        String presignedUrl = storageService.generatePresignedDownloadUrl(document.getFileKey(), expiryMinutes);

        return new DownloadUrlResponse(presignedUrl, (long) expiryMinutes * 60);
    }

    // -------------------------------------------------------------------------
    // Delete (soft)
    // -------------------------------------------------------------------------

    /**
     * Soft-deletes a document by setting its status to {@code "DELETED"}.
     *
     * <p>The corresponding S3 object is <em>not</em> deleted immediately; a
     * separate scheduled cleanup job handles physical removal to allow for
     * a recovery window.
     *
     * @param documentId  the UUID of the document to delete
     * @param workspaceId the workspace the document belongs to (prevents cross-workspace deletion)
     * @param principal   the authenticated caller
     * @throws ApiException (404) if the document cannot be found in the given workspace
     */
    @Transactional
    public void deleteDocument(UUID documentId, UUID workspaceId, UserPrincipal principal) {

        // Enforce: only OWNER or EDITOR can delete documents
        WorkspaceMember membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, principal.userId())
                .orElseThrow(() -> ApiException.forbidden("You are not a member of this workspace."));

        if (membership.getRole() == WorkspaceMember.MemberRole.VIEWER) {
            throw ApiException.forbidden("VIEWER role cannot delete documents. Only EDITOR or OWNER can delete.");
        }

        int updated = documentRepository.softDelete(documentId, workspaceId);
        if (updated == 0) {
            throw ApiException.notFound("Document not found or already deleted: " + documentId);
        }

        log.info("Document soft-deleted — id={} workspaceId={} userId={} role={}",
                documentId, workspaceId, principal.userId(), membership.getRole());

        // Evict analytics cache so download counts refresh
        try {
            analyticsService.evictWorkspaceStats(workspaceId);
        } catch (Exception ex) {
            log.warn("Analytics cache eviction failed (non-critical): {}", ex.getMessage());
        }

        publishAuditEvent(AuditEventType.DOCUMENT_DELETED, principal, workspaceId, documentId);
    }

    /**
     * Updates document metadata (name, description, tags).
     *
     * @param workspaceId the workspace the document belongs to
     * @param documentId  the UUID of the document to update
     * @param req         metadata update request
     * @param principal   the authenticated caller
     * @return the updated document response DTO
     */
    @Transactional
    public DocumentResponse updateDocument(UUID workspaceId, UUID documentId, UpdateMetadataRequest req, UserPrincipal principal) {
        // Enforce: only OWNER or EDITOR can edit documents
        WorkspaceMember membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, principal.userId())
                .orElseThrow(() -> ApiException.forbidden("You are not a member of this workspace."));

        if (membership.getRole() == WorkspaceMember.MemberRole.VIEWER) {
            throw ApiException.forbidden("VIEWER role cannot edit documents. Only EDITOR or OWNER can edit.");
        }

        Document document = documentRepository.findById(documentId)
                .filter(d -> !"DELETED".equals(d.getStatus()))
                .filter(d -> d.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> ApiException.notFound("Document not found: " + documentId));

        document.setName(req.name());
        document.setDescription(req.description());
        document.setTags(req.tags());
        document = documentRepository.save(document);

        log.info("Document metadata updated — id={} workspaceId={} userId={} role={}",
                documentId, workspaceId, principal.userId(), membership.getRole());

        // Evict analytics cache
        try {
            analyticsService.evictWorkspaceStats(workspaceId);
        } catch (Exception ex) {
            log.warn("Analytics cache eviction failed (non-critical): {}", ex.getMessage());
        }

        publishAuditEvent(AuditEventType.DOCUMENT_UPDATED, principal, workspaceId, documentId);

        return toResponse(document);
    }

    @Transactional
    public DocumentResponse updateDocumentContent(UUID workspaceId, UUID documentId, UpdateContentRequest req, UserPrincipal principal) {
        WorkspaceMember membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, principal.userId())
                .orElseThrow(() -> ApiException.forbidden("You are not a member of this workspace."));

        if (membership.getRole() == WorkspaceMember.MemberRole.VIEWER) {
            throw ApiException.forbidden("VIEWER role cannot edit documents. Only EDITOR or OWNER can edit.");
        }

        Document document = documentRepository.findById(documentId)
                .filter(d -> !"DELETED".equals(d.getStatus()))
                .filter(d -> d.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> ApiException.notFound("Document not found: " + documentId));

        if ("link".equals(document.getContentType())) {
            throw ApiException.badRequest("Cannot edit content of a link document.");
        }

        byte[] contentBytes = req.content() != null ? req.content().getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0];

        // Update in S3
        storageService.updateFileContent(document.getFileKey(), contentBytes, document.getContentType());

        // Update DB attributes: version, size, checksum
        document.setVersion(document.getVersion() + 1);
        document.setFileSizeBytes((long) contentBytes.length);
        document.setChecksumSha256(computeSha256(contentBytes));

        document = documentRepository.save(document);

        log.info("Document content updated — id={} workspaceId={} version={} size={}",
                documentId, workspaceId, document.getVersion(), document.getFileSizeBytes());

        // Evict analytics cache
        try {
            analyticsService.evictWorkspaceStats(workspaceId);
        } catch (Exception ex) {
            log.warn("Analytics cache eviction failed (non-critical): {}", ex.getMessage());
        }

        // Trigger AI summary asynchronously based on new content
        triggerAiSummaryAsync(document.getId(), contentBytes, document.getContentType());

        publishAuditEvent(AuditEventType.DOCUMENT_UPDATED, principal, workspaceId, documentId);

        return toResponse(document);
    }

    @Transactional
    public DocumentResponse setDocumentPassword(UUID workspaceId, UUID documentId, SetPasswordRequest req, UserPrincipal principal) {
        WorkspaceMember membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, principal.userId())
                .orElseThrow(() -> ApiException.forbidden("You are not a member of this workspace."));

        if (membership.getRole() != WorkspaceMember.MemberRole.OWNER) {
            throw ApiException.forbidden("Only OWNER can configure document password protection.");
        }

        Document document = documentRepository.findById(documentId)
                .filter(d -> !"DELETED".equals(d.getStatus()))
                .filter(d -> d.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> ApiException.notFound("Document not found: " + documentId));

        if (req.password() == null || req.password().isBlank()) {
            document.setPasswordHash(null);
            log.info("Password protection removed for document — id={} workspaceId={}", documentId, workspaceId);
        } else {
            document.setPasswordHash(passwordEncoder.encode(req.password()));
            log.info("Password protection set for document — id={} workspaceId={}", documentId, workspaceId);
        }

        document = documentRepository.save(document);
        publishAuditEvent(AuditEventType.DOCUMENT_UPDATED, principal, workspaceId, documentId);

        return toResponse(document);
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse verifyDocumentPasswordAndGetUrl(UUID workspaceId, UUID documentId, VerifyPasswordRequest req, String type, UserPrincipal principal) {
        Document document = documentRepository.findById(documentId)
                .filter(d -> !"DELETED".equals(d.getStatus()))
                .filter(d -> d.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> ApiException.notFound("Document not found: " + documentId));

        if (document.getPasswordHash() == null || document.getPasswordHash().isEmpty()) {
            throw ApiException.badRequest("This document is not password protected.");
        }

        if (!passwordEncoder.matches(req.password(), document.getPasswordHash())) {
            throw ApiException.badRequest("Invalid document password.");
        }

        int expiryMinutes = storageService.getDefaultPresignedUrlExpiryMinutes();
        String presignedUrl;
        if ("download".equalsIgnoreCase(type)) {
            presignedUrl = storageService.generatePresignedDownloadUrl(
                    document.getFileKey(), expiryMinutes, document.getName());
            publishAuditEvent(AuditEventType.DOCUMENT_DOWNLOADED, principal, workspaceId, documentId);
        } else {
            presignedUrl = storageService.generatePresignedDownloadUrl(document.getFileKey(), expiryMinutes);
        }

        return new DownloadUrlResponse(presignedUrl, (long) expiryMinutes * 60);
    }

    // -------------------------------------------------------------------------

    // Async AI summarisation
    // -------------------------------------------------------------------------

    /**
     * Triggers AI summarisation in a separate thread (Virtual Thread when the
     * executor is configured accordingly). Designed as a fire-and-forget
     * operation so that upload latency is not affected.
     *
     * <p>On completion, the AI service updates the document's {@code aiSummary}
     * field directly.
     *
     * @param documentId  the ID of the newly uploaded document
     * @param fileBytes   raw file content to summarise
     * @param contentType MIME type to help the AI choose the extraction strategy
     */
    @Async
    protected void triggerAiSummaryAsync(UUID documentId, byte[] fileBytes, String contentType) {
        try {
            log.debug("Triggering async AI summary — documentId={}", documentId);
            aiService.summariseDocument(documentId, fileBytes, contentType);
        } catch (Exception ex) {
            // Non-fatal: summarisation failure should not fail the upload
            log.error("Async AI summarisation failed — documentId={} error={}",
                    documentId, ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Maps a {@link Document} JPA entity to its public-facing {@link DocumentResponse} DTO.
     *
     * @param doc the entity to map
     * @return the corresponding DTO
     */
    private DocumentResponse toResponse(Document doc) {
        return new DocumentResponse(
                doc.getId(),
                doc.getWorkspaceId(),
                doc.getName(),
                doc.getDescription(),
                doc.getContentType(),
                doc.getFileSizeBytes(),
                doc.getVersion(),
                doc.getStatus(),
                doc.getTags(),
                doc.getAiSummary(),
                doc.getUploadedBy(),
                doc.getCreatedAt(),
                doc.getFolderId(),
                doc.getPasswordHash() != null && !doc.getPasswordHash().isEmpty(),
                doc.getFileKey()
        );
    }

    /**
     * Computes the SHA-256 hex digest of the given byte array.
     *
     * @param data the raw file bytes
     * @return lowercase hex-encoded SHA-256 digest (64 characters)
     * @throws ApiException (500) if the JVM unexpectedly lacks SHA-256 support
     */
    private String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // Should never happen on a modern JVM
            throw ApiException.internalError("SHA-256 algorithm not available: " + ex.getMessage());
        }
    }

    /**
     * Retrieves the text/code content of a document as a UTF-8 string.
     * Enforces membership, existence, and password validation.
     */
    @Transactional(readOnly = true)
    public String getDocumentContent(UUID workspaceId, UUID documentId, String password, UserPrincipal principal) {
        // 1. Verify workspace membership
        WorkspaceMember membership = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, principal.userId())
                .orElseThrow(() -> ApiException.forbidden("You are not a member of this workspace."));

        // 2. Verify document exists and belongs to the workspace
        Document document = documentRepository.findById(documentId)
                .filter(d -> !"DELETED".equals(d.getStatus()))
                .filter(d -> d.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> ApiException.notFound("Document not found: " + documentId));

        // 3. If password-protected, verify the password matches (bypassed for OWNER)
        if (document.getPasswordHash() != null && !document.getPasswordHash().isEmpty()) {
            if (membership.getRole() != WorkspaceMember.MemberRole.OWNER) {
                if (password == null || password.isBlank() || !passwordEncoder.matches(password, document.getPasswordHash())) {
                    throw ApiException.forbidden("This document is password protected. Correct password is required.");
                }
            }
        }

        // 4. Download file bytes and convert to a UTF-8 string
        byte[] fileBytes = storageService.downloadFile(document.getFileKey());
        return new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
    }


    /**
     * Builds and publishes a typed {@link DocumentAuditEvent} via the Spring
     * application event bus. Listeners (e.g. Kafka producers, WebSocket
     * notification handlers) pick up the event asynchronously.
     *
     * @param type        the type of audit event
     * @param principal   the actor responsible for the event
     * @param workspaceId the workspace context (may be {@code null})
     * @param documentId  the document involved (may be {@code null})
     */
    private void publishAuditEvent(AuditEventType type,
                                   UserPrincipal principal,
                                   UUID workspaceId,
                                   UUID documentId) {
        try {
            eventPublisher.publishEvent(
                    new DocumentAuditEvent(this, type, principal.userId(),
                            principal.organizationId(), workspaceId, documentId)
            );
        } catch (Exception ex) {
            // Audit event publishing must never break the primary flow
            log.error("Failed to publish audit event — type={} error={}", type, ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Nested helper: typed Spring ApplicationEvent for document audit
    // -------------------------------------------------------------------------

    /**
     * Lightweight Spring {@link org.springframework.context.ApplicationEvent}
     * published whenever a document lifecycle action occurs.
     *
     * <p>Defined as a nested record for cohesion; listeners in the audit domain
     * can subscribe using {@code @EventListener(DocumentAuditEvent.class)}.
     *
     * @param source         the originating service instance
     * @param eventType      the audit event category
     * @param userId         UUID of the user who triggered the event
     * @param organizationId UUID of the organisation
     * @param workspaceId    UUID of the workspace (may be {@code null})
     * @param documentId     UUID of the document involved (may be {@code null})
     */
    public record DocumentAuditEvent(
            Object source,
            AuditEventType eventType,
            UUID userId,
            UUID organizationId,
            UUID workspaceId,
            UUID documentId
    ) {
    }
}
