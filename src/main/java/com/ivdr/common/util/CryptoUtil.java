package com.ivdr.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Cryptographic utility component for the IVDR audit and security subsystem.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>HMAC-SHA256 signing and verification of audit event payloads</li>
 *   <li>Unique event ID generation</li>
 *   <li>SHA-256 hex digests for file integrity checks</li>
 * </ul>
 *
 * <p>The HMAC secret is read from the {@code app.jwt.secret} application property
 * so that the same secret used for JWT signing is reused for audit event signing,
 * reducing the number of secrets that must be managed.</p>
 */
@Component
public class CryptoUtil {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SHA256_ALGORITHM = "SHA-256";

    /**
     * The HMAC signing secret, injected from {@code app.jwt.secret}.
     * Ensure this value is at least 256 bits (32 characters) in production.
     */
    @Value("${app.jwt.secret}")
    private String defaultSecret;

    // -----------------------------------------------------------------------
    // HMAC-SHA256 Signing
    // -----------------------------------------------------------------------

    /**
     * Computes a Base64-encoded HMAC-SHA256 signature of the given payload
     * using the provided secret key.
     *
     * @param payload the plain-text string to sign
     * @param secret  the HMAC secret key; if {@code null} or blank, the
     *                configured {@code app.jwt.secret} is used instead
     * @return a URL-safe Base64-encoded HMAC-SHA256 digest
     * @throws IllegalStateException if the JVM does not support HmacSHA256 or
     *                               the secret key is invalid
     */
    public String hmacSha256(String payload, String secret) {
        try {
            String effectiveSecret = (secret == null || secret.isBlank()) ? defaultSecret : secret;
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    effectiveSecret.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            // Use URL-safe Base64 without padding to make the value safe for HTTP headers
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 algorithm not available on this JVM", e);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("Invalid HMAC key provided", e);
        }
    }

    /**
     * Convenience overload that uses the configured {@code app.jwt.secret} as
     * the HMAC key.
     *
     * @param payload the plain-text string to sign
     * @return a URL-safe Base64-encoded HMAC-SHA256 digest
     */
    public String hmacSha256(String payload) {
        return hmacSha256(payload, defaultSecret);
    }

    // -----------------------------------------------------------------------
    // HMAC Verification
    // -----------------------------------------------------------------------

    /**
     * Verifies that a Base64-encoded HMAC-SHA256 {@code signature} matches the
     * expected signature computed over {@code payload} with {@code secret}.
     *
     * <p>Uses a constant-time comparison via {@link MessageDigest#isEqual} to
     * prevent timing-based side-channel attacks.</p>
     *
     * @param payload   the original plain-text payload
     * @param signature the Base64-encoded signature to verify
     * @param secret    the HMAC secret key; if {@code null} or blank, falls back
     *                  to the configured {@code app.jwt.secret}
     * @return {@code true} if the signature is valid; {@code false} otherwise
     */
    public boolean verifyHmac(String payload, String signature, String secret) {
        if (payload == null || signature == null) {
            return false;
        }
        try {
            String expected = hmacSha256(payload, secret);
            byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
            byte[] actualBytes = signature.getBytes(StandardCharsets.UTF_8);
            // Constant-time equality check
            return MessageDigest.isEqual(expectedBytes, actualBytes);
        } catch (Exception e) {
            // Any exception during verification means the check failed
            return false;
        }
    }

    /**
     * Convenience overload using the configured {@code app.jwt.secret}.
     *
     * @param payload   the original plain-text payload
     * @param signature the Base64-encoded signature to verify
     * @return {@code true} if the signature is valid; {@code false} otherwise
     */
    public boolean verifyHmac(String payload, String signature) {
        return verifyHmac(payload, signature, defaultSecret);
    }

    // -----------------------------------------------------------------------
    // Event ID Generation
    // -----------------------------------------------------------------------

    /**
     * Generates a cryptographically random UUID suitable for use as an audit
     * event identifier.
     *
     * @return a random UUID string in the standard {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx} format
     */
    public String generateEventId() {
        return UUID.randomUUID().toString();
    }

    // -----------------------------------------------------------------------
    // File Checksum
    // -----------------------------------------------------------------------

    /**
     * Computes the SHA-256 hex digest of the supplied raw bytes, suitable for
     * verifying the integrity of uploaded or stored files.
     *
     * <p>Example usage:</p>
     * <pre>{@code
     *   byte[] fileBytes = Files.readAllBytes(path);
     *   String checksum = cryptoUtil.sha256Hex(fileBytes);
     * }</pre>
     *
     * @param data the raw byte array to hash; must not be {@code null}
     * @return a lowercase hex-encoded SHA-256 digest (64 characters)
     * @throws IllegalStateException if SHA-256 is not available on this JVM
     */
    public String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA256_ALGORITHM);
            byte[] hashBytes = digest.digest(data);
            // Java 17+ HexFormat for zero-dependency hex encoding
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available on this JVM", e);
        }
    }
}
