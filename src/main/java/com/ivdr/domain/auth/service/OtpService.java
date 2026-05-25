package com.ivdr.domain.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Service that generates and verifies 6-digit OTP codes for email verification.
 * OTPs are stored in Redis with a 10-minute TTL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    private static final int OTP_LENGTH = 6;
    private static final long OTP_TTL_MINUTES = 10;
    private static final String OTP_KEY_PREFIX = "otp:register:";
    private static final int MAX_ATTEMPTS = 5;
    private static final String ATTEMPT_KEY_PREFIX = "otp:attempts:";

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a 6-digit OTP and stores it in Redis for the given email.
     * Overwrites any previously stored OTP.
     *
     * @param email the email address to associate the OTP with
     * @return the generated OTP string
     */
    public String generateAndStore(String email) {
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        String key = OTP_KEY_PREFIX + email.toLowerCase();

        redisTemplate.opsForValue().set(key, otp, Duration.ofMinutes(OTP_TTL_MINUTES));
        // Reset attempt counter
        redisTemplate.delete(ATTEMPT_KEY_PREFIX + email.toLowerCase());

        log.info("OTP generated and stored for email: {} (expires in {} min)", email, OTP_TTL_MINUTES);
        return otp;
    }

    /**
     * Verifies the OTP entered by the user.
     *
     * @param email       the email address
     * @param enteredOtp  the OTP entered by the user
     * @return true if correct, false if wrong or expired
     */
    public boolean verify(String email, String enteredOtp) {
        String key = OTP_KEY_PREFIX + email.toLowerCase();
        String attemptKey = ATTEMPT_KEY_PREFIX + email.toLowerCase();

        String storedOtp = redisTemplate.opsForValue().get(key);
        if (storedOtp == null) {
            log.warn("OTP verification failed: OTP expired or not found for email={}", email);
            return false;
        }

        // Increment attempt counter
        Long attempts = redisTemplate.opsForValue().increment(attemptKey);
        redisTemplate.expire(attemptKey, Duration.ofMinutes(OTP_TTL_MINUTES));

        if (attempts != null && attempts > MAX_ATTEMPTS) {
            log.warn("OTP verification: too many attempts for email={}", email);
            redisTemplate.delete(key);
            return false;
        }

        if (storedOtp.equals(enteredOtp.trim())) {
            // Invalidate OTP after successful verification
            redisTemplate.delete(key);
            redisTemplate.delete(attemptKey);
            log.info("OTP verified successfully for email: {}", email);
            return true;
        }

        log.warn("OTP mismatch for email={}, attempt={}", email, attempts);
        return false;
    }

    /**
     * Checks if an OTP is currently pending (not expired) for the given email.
     *
     * @param email the email to check
     * @return true if a pending OTP exists
     */
    public boolean hasPendingOtp(String email) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(OTP_KEY_PREFIX + email.toLowerCase())
        );
    }

    /**
     * Invalidates any OTP for the given email (e.g., on registration success).
     *
     * @param email the email to invalidate OTP for
     */
    public void invalidate(String email) {
        redisTemplate.delete(OTP_KEY_PREFIX + email.toLowerCase());
        redisTemplate.delete(ATTEMPT_KEY_PREFIX + email.toLowerCase());
    }
}
