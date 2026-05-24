package com.ivdr.domain.document.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis-backed sliding-window rate limiter.
 *
 * <h2>Algorithm</h2>
 * <p>The sliding window is implemented using a Redis <em>sorted set</em> (ZSET)
 * where each member is a unique request identifier (timestamp in nanoseconds +
 * random suffix) and the score is the Unix timestamp in milliseconds at which
 * the request arrived.
 *
 * <p>For each call to {@link #isAllowed}:
 * <ol>
 *   <li>Remove all members with a score older than
 *       {@code now - windowSeconds * 1000} ms — these are outside the window.</li>
 *   <li>Count the remaining members (requests inside the window).</li>
 *   <li>If the count is below {@code maxRequests}, add the current request and
 *       set/refresh the TTL on the key; return {@code true}.</li>
 *   <li>Otherwise return {@code false} (rate limit exceeded).</li>
 * </ol>
 *
 * <p>All four operations are executed atomically inside a single Lua script,
 * eliminating race conditions that would occur with a plain pipeline.
 *
 * <h2>Key format</h2>
 * <pre>rate_limit:{action}:{userId}</pre>
 *
 * <h2>Thread safety</h2>
 * <p>{@link RedisTemplate} is thread-safe by design; this service can be used
 * concurrently from Virtual Threads without additional synchronisation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    /**
     * Spring Data Redis template.
     * Key type: {@code String}; value type: {@code Object} (serialised by the
     * configured {@link org.springframework.data.redis.serializer.RedisSerializer}).
     */
    private final RedisTemplate<String, Object> redisTemplate;

    // -------------------------------------------------------------------------
    // Lua script — atomic sliding window
    // -------------------------------------------------------------------------

    /**
     * Lua script executed atomically on the Redis server.
     *
     * <p>KEYS[1] — the rate-limit sorted-set key<br>
     * ARGV[1] — current Unix time in milliseconds (as string)<br>
     * ARGV[2] — window start time in milliseconds (now - window * 1000)<br>
     * ARGV[3] — maximum allowed requests per window<br>
     * ARGV[4] — window duration in seconds (used as EXPIRE TTL)<br>
     *
     * <p>Returns 1 if the request is allowed, 0 if the rate limit is exceeded.
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT;

    static {
        SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();
        SLIDING_WINDOW_SCRIPT.setResultType(Long.class);
        SLIDING_WINDOW_SCRIPT.setScriptText("""
                -- KEYS[1]: sorted-set key
                -- ARGV[1]: nowMs  (current time in ms)
                -- ARGV[2]: windowStartMs  (now - windowSeconds * 1000)
                -- ARGV[3]: maxRequests
                -- ARGV[4]: windowSeconds (TTL)
                
                local key            = KEYS[1]
                local nowMs          = tonumber(ARGV[1])
                local windowStartMs  = tonumber(ARGV[2])
                local maxRequests    = tonumber(ARGV[3])
                local windowSeconds  = tonumber(ARGV[4])
                
                -- 1. Remove stale entries (outside the sliding window)
                redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStartMs)
                
                -- 2. Count requests still inside the window
                local count = redis.call('ZCARD', key)
                
                -- 3. Decide: allow or reject
                if count < maxRequests then
                    -- Use nowMs + a random nano suffix as a unique member to handle
                    -- multiple requests at the exact same millisecond.
                    local member = nowMs .. '-' .. math.random(1, 1000000)
                    redis.call('ZADD', key, nowMs, member)
                    redis.call('EXPIRE', key, windowSeconds + 1)
                    return 1   -- allowed
                else
                    return 0   -- rejected
                end
                """);
    }

    /**
     * Lua script that only reads the current count without mutating state,
     * used by {@link #remainingRequests}.
     *
     * <p>KEYS[1] — the rate-limit sorted-set key<br>
     * ARGV[1] — window start time in milliseconds<br>
     * ARGV[2] — maximum allowed requests per window<br>
     *
     * <p>Returns the number of <em>remaining</em> requests in the current window.
     */
    private static final DefaultRedisScript<Long> COUNT_SCRIPT;

    static {
        COUNT_SCRIPT = new DefaultRedisScript<>();
        COUNT_SCRIPT.setResultType(Long.class);
        COUNT_SCRIPT.setScriptText("""
                local key           = KEYS[1]
                local windowStartMs = tonumber(ARGV[1])
                local maxRequests   = tonumber(ARGV[2])
                
                redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStartMs)
                local count = redis.call('ZCARD', key)
                local remaining = maxRequests - count
                if remaining < 0 then remaining = 0 end
                return remaining
                """);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Checks whether the action identified by {@code key} is within the
     * configured rate limit and, if so, records the request atomically.
     *
     * <p>The {@code key} should follow the canonical format:
     * <pre>rate_limit:{action}:{userId}</pre>
     *
     * @param key           the Redis sorted-set key that tracks this action
     * @param maxRequests   maximum number of requests allowed per window
     * @param windowSeconds sliding window duration in seconds
     * @return {@code true} if the request is within the limit and has been
     *         counted; {@code false} if the rate limit has been exceeded
     */
    public boolean isAllowed(String key, int maxRequests, int windowSeconds) {
        long nowMs         = System.currentTimeMillis();
        long windowStartMs = nowMs - (windowSeconds * 1000L);

        try {
            Long result = redisTemplate.execute(
                    SLIDING_WINDOW_SCRIPT,
                    List.of(key),
                    String.valueOf(nowMs),
                    String.valueOf(windowStartMs),
                    String.valueOf(maxRequests),
                    String.valueOf(windowSeconds)
            );

            boolean allowed = Long.valueOf(1L).equals(result);
            if (!allowed) {
                log.warn("Rate limit exceeded — key={} maxRequests={} windowSeconds={}",
                        key, maxRequests, windowSeconds);
            }
            return allowed;

        } catch (Exception ex) {
            // Fail open: if Redis is unavailable we allow the request to proceed
            // rather than blocking all traffic. Log at ERROR so on-call is alerted.
            log.error("Rate limiter Redis error for key={} — failing open. Error: {}",
                    key, ex.getMessage(), ex);
            return true;
        }
    }

    /**
     * Returns the number of requests remaining in the current sliding window
     * for the given key without recording a new request.
     *
     * <p>This method is suitable for including in HTTP response headers
     * (e.g. {@code X-RateLimit-Remaining}) so that clients can back off
     * gracefully before hitting the limit.
     *
     * @param key           the Redis sorted-set key that tracks this action
     * @param maxRequests   maximum number of requests allowed per window
     * @param windowSeconds sliding window duration in seconds
     * @return number of remaining requests; 0 if at or above the limit;
     *         {@code maxRequests} if Redis is unavailable
     */
    public int remainingRequests(String key, int maxRequests, int windowSeconds) {
        long nowMs         = System.currentTimeMillis();
        long windowStartMs = nowMs - (windowSeconds * 1000L);

        try {
            Long remaining = redisTemplate.execute(
                    COUNT_SCRIPT,
                    List.of(key),
                    String.valueOf(windowStartMs),
                    String.valueOf(maxRequests)
            );

            return remaining != null ? remaining.intValue() : maxRequests;

        } catch (Exception ex) {
            log.error("Rate limiter remaining-count Redis error for key={} — returning max. Error: {}",
                    key, ex.getMessage(), ex);
            return maxRequests;
        }
    }

    // -------------------------------------------------------------------------
    // Key builder helper
    // -------------------------------------------------------------------------

    /**
     * Builds a canonical rate-limit Redis key.
     *
     * <p>Callers are encouraged to use this helper rather than constructing
     * keys manually, to ensure consistent formatting across the codebase.
     *
     * @param action the action being rate-limited (e.g. {@code "upload"}, {@code "download"})
     * @param userId the user identifier (e.g. UUID string or email)
     * @return the Redis key string in the format {@code rate_limit:{action}:{userId}}
     */
    public static String buildKey(String action, String userId) {
        return "rate_limit:" + action + ":" + userId;
    }
}
