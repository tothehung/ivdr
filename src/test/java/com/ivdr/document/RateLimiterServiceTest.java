package com.ivdr.document;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@code RateLimiterService}.
 *
 * <p>Uses a <em>sliding-window / token-bucket</em> algorithm backed by a
 * Redis sorted-set (ZSET).  The window size and limit are:
 * <ul>
 *   <li>Window: 60 seconds</li>
 *   <li>Max requests per window: {@code MAX_REQUESTS} (default 10)</li>
 * </ul>
 *
 * <p>For each call to {@code isAllowed(key)}:
 * <ol>
 *   <li>Remove expired entries older than {@code now - windowSizeMs}.</li>
 *   <li>Add the current request with score = current epoch millis.</li>
 *   <li>Count remaining entries with {@code ZCARD}.</li>
 *   <li>Return {@code true} iff count ≤ {@code MAX_REQUESTS}.</li>
 * </ol>
 *
 * <p>{@code RateLimiterService} is planned (the document/service directory is
 * currently empty).  This test file drives the contract; the implementation
 * must pass all tests below.  A self-contained inner class is provided so the
 * test compiles immediately — delete it once the real class is written in
 * {@code com.ivdr.domain.document.service}.
 */
@ExtendWith(MockitoExtension.class)
@Slf4j
class RateLimiterServiceTest {

    // -------------------------------------------------------------------------
    // Mocked dependencies
    // -------------------------------------------------------------------------

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    // -------------------------------------------------------------------------
    // Subject under test
    // -------------------------------------------------------------------------

    private RateLimiterService rateLimiterService;

    // -------------------------------------------------------------------------
    // Test constants
    // -------------------------------------------------------------------------

    private static final int  MAX_REQUESTS   = 10;
    private static final long WINDOW_SIZE_MS = 60_000L;
    private static final String CLIENT_KEY   = "rate:user:" + UUID.randomUUID();

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        // Wire the ZSetOperations mock through the RedisTemplate mock
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        rateLimiterService = new RateLimiterService(redisTemplate, MAX_REQUESTS, WINDOW_SIZE_MS);
    }

    // =========================================================================
    // 1. isAllowed_firstRequest_returnsTrue
    // =========================================================================

    @Test
    @DisplayName("isAllowed() — very first request for a key — returns true")
    void isAllowed_firstRequest_returnsTrue() {
        // ── Arrange ──────────────────────────────────────────────────────────
        // Expired entries cleanup: nothing removed (0)
        when(zSetOperations.removeRangeByScore(eq(CLIENT_KEY), eq(0.0), anyDouble()))
                .thenReturn(0L);

        // ZADD succeeds (1 member added)
        when(zSetOperations.add(eq(CLIENT_KEY), anyString(), anyDouble()))
                .thenReturn(true);

        // ZCARD = 1 (only this request in the window)
        when(zSetOperations.zCard(CLIENT_KEY)).thenReturn(1L);

        // ── Act ───────────────────────────────────────────────────────────────
        boolean allowed = rateLimiterService.isAllowed(CLIENT_KEY);

        // ── Assert ────────────────────────────────────────────────────────────
        assertThat(allowed).isTrue();

        // Verify sliding-window pipeline was executed
        verify(zSetOperations).removeRangeByScore(eq(CLIENT_KEY), eq(0.0), anyDouble());
        verify(zSetOperations).add(eq(CLIENT_KEY), anyString(), anyDouble());
        verify(zSetOperations).zCard(CLIENT_KEY);

        log.info("isAllowed_firstRequest_returnsTrue passed");
    }

    // =========================================================================
    // 2. isAllowed_underLimit_returnsTrue
    // =========================================================================

    @Test
    @DisplayName("isAllowed() — current count well below limit — returns true")
    void isAllowed_underLimit_returnsTrue() {
        // ── Arrange ──────────────────────────────────────────────────────────
        when(zSetOperations.removeRangeByScore(eq(CLIENT_KEY), eq(0.0), anyDouble()))
                .thenReturn(0L);
        when(zSetOperations.add(eq(CLIENT_KEY), anyString(), anyDouble())).thenReturn(true);

        // 5 out of 10 requests used — well within limit
        when(zSetOperations.zCard(CLIENT_KEY)).thenReturn(5L);

        // ── Act & Assert ──────────────────────────────────────────────────────
        assertThat(rateLimiterService.isAllowed(CLIENT_KEY)).isTrue();

        log.info("isAllowed_underLimit_returnsTrue passed: count=5, max={}", MAX_REQUESTS);
    }

    // =========================================================================
    // 3. isAllowed_atLimit_returnsFalse
    // =========================================================================

    @Test
    @DisplayName("isAllowed() — current count equals limit — returns false")
    void isAllowed_atLimit_returnsFalse() {
        // ── Arrange ──────────────────────────────────────────────────────────
        when(zSetOperations.removeRangeByScore(eq(CLIENT_KEY), eq(0.0), anyDouble()))
                .thenReturn(0L);
        when(zSetOperations.add(eq(CLIENT_KEY), anyString(), anyDouble())).thenReturn(true);

        // ZCARD = MAX_REQUESTS → limit hit, this request pushed it over
        when(zSetOperations.zCard(CLIENT_KEY)).thenReturn((long) MAX_REQUESTS + 1);

        // ── Act & Assert ──────────────────────────────────────────────────────
        boolean allowed = rateLimiterService.isAllowed(CLIENT_KEY);

        assertThat(allowed)
                .as("Request count exceeds limit — should be rejected")
                .isFalse();

        log.info("isAllowed_atLimit_returnsFalse passed: count={}, max={}", MAX_REQUESTS + 1, MAX_REQUESTS);
    }

    // =========================================================================
    // 4. isAllowed_expiredWindowRequests_countedCorrectly
    // =========================================================================

    @Test
    @DisplayName("isAllowed() — expired entries pruned — only in-window requests counted")
    void isAllowed_expiredWindowRequests_countedCorrectly() {
        // ── Arrange ──────────────────────────────────────────────────────────
        // 8 old entries are pruned (they were outside the current window)
        when(zSetOperations.removeRangeByScore(eq(CLIENT_KEY), eq(0.0), anyDouble()))
                .thenReturn(8L);

        // After pruning, add the new request
        when(zSetOperations.add(eq(CLIENT_KEY), anyString(), anyDouble())).thenReturn(true);

        // After pruning 8 old + 2 remaining + 1 new = 3 in window → under limit
        when(zSetOperations.zCard(CLIENT_KEY)).thenReturn(3L);

        // ── Act ───────────────────────────────────────────────────────────────
        boolean allowed = rateLimiterService.isAllowed(CLIENT_KEY);

        // ── Assert ────────────────────────────────────────────────────────────
        assertThat(allowed)
                .as("After pruning expired entries, count (3) is below limit (%d)", MAX_REQUESTS)
                .isTrue();

        // Expired entries MUST have been removed before counting
        verify(zSetOperations).removeRangeByScore(
                eq(CLIENT_KEY),
                eq(0.0),           // min score (oldest possible)
                anyDouble()        // max score = now - window — tested via behaviour
        );
        verify(zSetOperations).add(eq(CLIENT_KEY), anyString(), anyDouble());
        verify(zSetOperations).zCard(CLIENT_KEY);

        log.info("isAllowed_expiredWindowRequests_countedCorrectly passed: "
                + "pruned=8, remaining=3, max={}", MAX_REQUESTS);
    }

    // =========================================================================
    // Placeholder implementation
    // =========================================================================
    // Delete this inner class once the real RateLimiterService is created at
    // com.ivdr.domain.document.service.RateLimiterService and imported above.

    /**
     * Self-contained rate limiter backed by a Redis sorted-set sliding window.
     *
     * <p>Delete this inner class once the real production class exists.
     */
    static class RateLimiterService {

        private final RedisTemplate<String, String> redisTemplate;
        private final int  maxRequests;
        private final long windowSizeMs;

        RateLimiterService(RedisTemplate<String, String> redisTemplate,
                           int  maxRequests,
                           long windowSizeMs) {
            this.redisTemplate = redisTemplate;
            this.maxRequests   = maxRequests;
            this.windowSizeMs  = windowSizeMs;
        }

        /**
         * Returns {@code true} if the caller identified by {@code key} is
         * allowed to proceed under the current rate-limit policy.
         *
         * <p>Algorithm:
         * <ol>
         *   <li>Remove ZSET members with score &lt; (now − window) — they are expired.</li>
         *   <li>Add the current request with score = {@code System.currentTimeMillis()}.</li>
         *   <li>Count remaining members with ZCARD.</li>
         *   <li>Return {@code true} iff count &le; {@code maxRequests}.</li>
         * </ol>
         *
         * @param key a unique identifier for the rate-limit bucket
         *            (e.g. {@code "rate:user:<userId>"})
         * @return {@code true} if the request is permitted; {@code false} if throttled
         */
        public boolean isAllowed(String key) {
            long now        = System.currentTimeMillis();
            long windowStart = now - windowSizeMs;

            ZSetOperations<String, String> ops = redisTemplate.opsForZSet();

            // 1. Prune entries that fall outside the sliding window
            ops.removeRangeByScore(key, 0.0, (double) windowStart);

            // 2. Record this request (unique member = UUID, score = epoch ms)
            ops.add(key, UUID.randomUUID().toString(), (double) now);

            // 3. Count in-window requests
            Long count = ops.zCard(key);
            long current = (count != null) ? count : 0L;

            boolean allowed = current <= maxRequests;

            log.debug("[RateLimiter] key={} count={} max={} allowed={}", key, current, maxRequests, allowed);
            return allowed;
        }
    }
}
