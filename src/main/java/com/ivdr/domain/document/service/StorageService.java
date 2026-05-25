package com.ivdr.domain.document.service;

import com.ivdr.common.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

/**
 * Service responsible for all interactions with AWS S3 (or an S3-compatible store).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Uploading file streams with structured key naming.</li>
 *   <li>Generating short-lived pre-signed download URLs.</li>
 *   <li>Deleting objects from the bucket.</li>
 * </ul>
 *
 * <p>Required application properties:
 * <pre>
 *   ivdr.storage.bucket-name=your-s3-bucket-name
 *   ivdr.storage.presigned-url-expiry-minutes=15
 * </pre>
 *
 * <p>The AWS SDK clients ({@link S3Client} and {@link S3Presigner}) are expected
 * to be provided as Spring beans (configured in a separate {@code AwsConfig} class
 * that reads {@code aws.region}, access-key, and secret-key from application
 * properties / environment variables).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    /** Synchronous S3 client bean — used for PUT and DELETE operations. */
    private final S3Client s3Client;

    /**
     * S3 pre-signer bean — separated from the main client because pre-signing
     * is a pure URL-generation operation that never makes a network call.
     */
    private final S3Presigner s3Presigner;

    /** Target S3 bucket name injected from application configuration. */
    @Value("${app.storage.s3.bucket}")
    private String bucketName;

    /**
     * Default pre-signed URL TTL in minutes — overridden per call if needed.
     * Injected from application configuration.
     */
    @Value("${app.storage.s3.presigned-url-expiry-minutes:15}")
    private int defaultPresignedUrlExpiryMinutes;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Uploads a file stream to S3 and returns the generated object key.
     *
     * <p>Key format: {@code {orgId}/{workspaceId}/{randomUUID}-{filename}}
     *
     * <p>This method blocks until the upload completes (or fails). For very large
     * files consider replacing the synchronous client with the
     * {@code S3TransferManager} multipart-upload path.
     *
     * @param orgId       the organisation UUID (first path segment)
     * @param workspaceId the workspace UUID (second path segment)
     * @param filename    the original client-supplied filename (appended after UUID)
     * @param inputStream the raw byte stream of the file; must not be {@code null}
     * @param size        exact byte length of the stream (required by S3 SDK)
     * @param contentType MIME type of the file (e.g. {@code application/pdf})
     * @return the S3 object key that was used to store the file
     * @throws ApiException if the upload fails for any reason
     */
    public String uploadFile(UUID orgId,
                             UUID workspaceId,
                             String filename,
                             InputStream inputStream,
                             long size,
                             String contentType) {

        String fileKey = buildKey(orgId, workspaceId, filename);
        log.debug("Uploading file to S3 — bucket={} key={} size={} contentType={}",
                bucketName, fileKey, size, contentType);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileKey)
                .contentType(contentType)
                .contentLength(size)
                .build();

        byte[] bytes;
        try {
            bytes = inputStream.readAllBytes();
        } catch (IOException e) {
            throw ApiException.internalError("Failed to read upload stream: " + e.getMessage());
        }

        try {
            s3Client.putObject(putRequest, RequestBody.fromBytes(bytes));
            log.info("File uploaded successfully — key={}", fileKey);
            return fileKey;
        } catch (software.amazon.awssdk.services.s3.model.NoSuchBucketException ex) {
            log.info("Bucket '{}' does not exist during upload. Creating and retrying...", bucketName);
            try {
                s3Client.createBucket(software.amazon.awssdk.services.s3.model.CreateBucketRequest.builder().bucket(bucketName).build());
                s3Client.putObject(putRequest, RequestBody.fromBytes(bytes));
                log.info("File uploaded successfully after creating bucket — key={}", fileKey);
                return fileKey;
            } catch (Exception retryEx) {
                log.error("Failed to upload after creating bucket: {}", retryEx.getMessage(), retryEx);
                throw ApiException.internalError("Failed to upload file to storage: " + retryEx.getMessage());
            }
        } catch (Exception ex) {
            if (ex instanceof software.amazon.awssdk.services.s3.model.S3Exception s3Ex && s3Ex.statusCode() == 404) {
                log.info("Bucket '{}' does not exist (404) during upload. Creating and retrying...", bucketName);
                try {
                    s3Client.createBucket(software.amazon.awssdk.services.s3.model.CreateBucketRequest.builder().bucket(bucketName).build());
                    s3Client.putObject(putRequest, RequestBody.fromBytes(bytes));
                    log.info("File uploaded successfully after creating bucket — key={}", fileKey);
                    return fileKey;
                } catch (Exception retryEx) {
                    log.error("Failed to upload after creating bucket: {}", retryEx.getMessage(), retryEx);
                    throw ApiException.internalError("Failed to upload file to storage: " + retryEx.getMessage());
                }
            }
            log.error("S3 upload failed — key={} error={}", fileKey, ex.getMessage(), ex);
            throw ApiException.internalError("Failed to upload file to storage: " + ex.getMessage());
        }
    }

    /**
     * Generates a pre-signed HTTPS URL that grants temporary, unauthenticated
     * GET access to the S3 object identified by {@code fileKey}.
     *
     * <p>The URL is valid for {@code expiryMinutes} minutes from the moment this
     * method is called. Clients should cache the URL and not request a new one
     * on every page render.
     *
     * @param fileKey       the S3 object key returned by {@link #uploadFile}
     * @param expiryMinutes lifetime of the URL in minutes (typically 10–60)
     * @return an HTTPS pre-signed URL string
     * @throws ApiException if URL generation fails
     */
    public String generatePresignedDownloadUrl(String fileKey, int expiryMinutes) {
        return generatePresignedDownloadUrl(fileKey, expiryMinutes, null);
    }

    /**
     * Generates a pre-signed HTTPS URL for the given S3 object.
     *
     * @param fileKey       the S3 object key
     * @param expiryMinutes lifetime of the URL in minutes
     * @param filename      if non-null, adds Content-Disposition: attachment header to force download
     * @return an HTTPS pre-signed URL string
     */
    public String generatePresignedDownloadUrl(String fileKey, int expiryMinutes, String filename) {
        log.debug("Generating pre-signed URL — key={} expiryMinutes={} forceDownload={}",
                fileKey, expiryMinutes, filename != null);

        try {
            var getObjectRequestBuilder = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey);

            // If filename is provided, force browser to download instead of rendering inline
            if (filename != null && !filename.isBlank()) {
                String safeFilename = filename.replaceAll("[^\\x20-\\x7E]", "_");
                getObjectRequestBuilder.responseContentDisposition(
                        "attachment; filename=\"" + safeFilename + "\""
                );
            }

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(expiryMinutes))
                    .getObjectRequest(getObjectRequestBuilder.build())
                    .build();

            PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
            String url = presigned.url().toExternalForm();
            log.debug("Pre-signed URL generated — key={} url_prefix={}...", fileKey,
                    url.substring(0, Math.min(60, url.length())));
            return url;

        } catch (Exception ex) {
            log.error("Pre-signed URL generation failed — key={} error={}", fileKey, ex.getMessage(), ex);
            throw ApiException.internalError("Failed to generate download URL: " + ex.getMessage());
        }
    }

    /**
     * Deletes the S3 object identified by {@code fileKey}.
     *
     * <p>This operation is idempotent — deleting a non-existent key does not
     * throw an exception (S3 returns HTTP 204 regardless).
     *
     * @param fileKey the S3 object key to delete
     * @throws ApiException if the deletion request fails due to a network or
     *                      permission error
     */
    public void deleteFile(String fileKey) {
        log.debug("Deleting S3 object — bucket={} key={}", bucketName, fileKey);

        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileKey)
                    .build();

            s3Client.deleteObject(deleteRequest);
            log.info("S3 object deleted — key={}", fileKey);

        } catch (Exception ex) {
            log.error("S3 delete failed — key={} error={}", fileKey, ex.getMessage(), ex);
            throw ApiException.internalError("Failed to delete file from storage: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Default presigned expiry accessor (for use by callers that want the
    // configured default without hard-coding the minutes value)
    // -------------------------------------------------------------------------

    /**
     * Returns the default pre-signed URL expiry duration in minutes as
     * configured by {@code ivdr.storage.presigned-url-expiry-minutes}.
     *
     * @return default expiry in minutes
     */
    public int getDefaultPresignedUrlExpiryMinutes() {
        return defaultPresignedUrlExpiryMinutes;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Constructs an S3 object key using the canonical IVDR format:
     * <pre>{orgId}/{workspaceId}/{randomUUID}-{sanitisedFilename}</pre>
     *
     * <p>The random UUID prefix ensures uniqueness even if the same filename is
     * uploaded multiple times to the same workspace.
     *
     * @param orgId       organisation UUID
     * @param workspaceId workspace UUID
     * @param filename    original filename (will be sanitised)
     * @return formatted S3 key string
     */
    private String buildKey(UUID orgId, UUID workspaceId, String filename) {
        // Sanitise filename: replace whitespace and non-safe URL characters
        String safeFilename = filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        return "%s/%s/%s-%s".formatted(orgId, workspaceId, UUID.randomUUID(), safeFilename);
    }
}
