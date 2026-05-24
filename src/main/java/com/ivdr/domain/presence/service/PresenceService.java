package com.ivdr.domain.presence.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages real-time user presence for IVDR workspaces.
 *
 * <p>Presence state is persisted in Redis with a short TTL so stale sessions
 * are cleaned up automatically even if a client disconnects abruptly without
 * sending a LEAVE message.  Clients are expected to send periodic
 * {@code heartbeat} frames to keep their TTL alive.
 *
 * <p>Every state change (JOIN / LEAVE / VIEW) is broadcast to all subscribers of
 * {@code /topic/presence/{workspaceId}} via STOMP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${app.presence.session-ttl-seconds:30}")
    private int ttl;

    private String presenceKey(UUID workspaceId, UUID userId) {
        return "presence:" + workspaceId + ":" + userId;
    }

    private String presencePattern(UUID workspaceId) {
        return "presence:" + workspaceId + ":*";
    }

    private String presenceTopic(UUID workspaceId) {
        return "/topic/presence/" + workspaceId;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Records that a user has joined a workspace and broadcasts a JOIN event.
     * Stored in Redis as "sessionId|" (no document initially).
     */
    public void userJoined(UUID workspaceId, UUID userId, String sessionId) {
        String key = presenceKey(workspaceId, userId);
        String val = sessionId + "|";
        redisTemplate.opsForValue().set(key, val, ttl, TimeUnit.SECONDS);

        log.debug("User {} joined workspace {} (session={})", userId, workspaceId, sessionId);

        PresenceEvent event = new PresenceEvent(
                PresenceEventType.JOIN.name(),
                userId,
                sessionId,
                null,
                Instant.now()
        );
        messagingTemplate.convertAndSend(presenceTopic(workspaceId), event);
    }

    /**
     * Updates the user's presence to show they are currently viewing a document.
     */
    public void userViewedDocument(UUID workspaceId, UUID userId, UUID documentId) {
        String key = presenceKey(workspaceId, userId);
        String val = (String) redisTemplate.opsForValue().get(key);
        String sessionId = "unknown";

        if (val != null) {
            String[] parts = val.split("\\|");
            if (parts.length > 0) {
                sessionId = parts[0];
            }
        }

        String newVal = sessionId + "|" + (documentId != null ? documentId.toString() : "");
        redisTemplate.opsForValue().set(key, newVal, ttl, TimeUnit.SECONDS);

        log.debug("User {} viewed document {} in workspace {}", userId, documentId, workspaceId);

        PresenceEvent event = new PresenceEvent(
                PresenceEventType.VIEW.name(),
                userId,
                sessionId,
                documentId,
                Instant.now()
        );
        messagingTemplate.convertAndSend(presenceTopic(workspaceId), event);
    }

    /**
     * Removes a user's presence record and broadcasts a LEAVE event.
     */
    public void userLeft(UUID workspaceId, UUID userId) {
        String key = presenceKey(workspaceId, userId);
        String val = (String) redisTemplate.opsForValue().get(key);
        redisTemplate.delete(key);

        log.debug("User {} left workspace {}", userId, workspaceId);

        String sessionId = null;
        UUID documentId = null;

        if (val != null) {
            String[] parts = val.split("\\|");
            if (parts.length > 0) {
                sessionId = parts[0];
            }
            if (parts.length > 1 && !parts[1].isBlank()) {
                try {
                    documentId = UUID.fromString(parts[1]);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        PresenceEvent event = new PresenceEvent(
                PresenceEventType.LEAVE.name(),
                userId,
                sessionId,
                documentId,
                Instant.now()
        );
        messagingTemplate.convertAndSend(presenceTopic(workspaceId), event);
    }

    /**
     * Refreshes the TTL of a user's presence key without changing the value.
     */
    public void heartbeat(UUID workspaceId, UUID userId) {
        String key = presenceKey(workspaceId, userId);
        Boolean existed = redisTemplate.expire(key, ttl, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(existed)) {
            log.warn("Heartbeat for user {} in workspace {} — key not found", userId, workspaceId);
        } else {
            log.trace("Heartbeat refreshed for user {} in workspace {}", userId, workspaceId);
        }
    }

    /**
     * Returns the list of user IDs currently present in a workspace.
     */
    public List<UUID> getActiveUsers(UUID workspaceId) {
        Set<String> keys = redisTemplate.keys(presencePattern(workspaceId));
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        String prefix = "presence:" + workspaceId + ":";
        return keys.stream()
                .filter(Objects::nonNull)
                .map(k -> k.substring(prefix.length()))
                .map(s -> {
                    try {
                        return UUID.fromString(s);
                    } catch (IllegalArgumentException ex) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Returns detailed list of active user presences (including current document context).
     */
    public List<PresenceDetail> getActivePresences(UUID workspaceId) {
        Set<String> keys = redisTemplate.keys(presencePattern(workspaceId));
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        String prefix = "presence:" + workspaceId + ":";
        return keys.stream()
                .filter(Objects::nonNull)
                .map(k -> {
                    String userUuidStr = k.substring(prefix.length());
                    try {
                        UUID userId = UUID.fromString(userUuidStr);
                        String val = (String) redisTemplate.opsForValue().get(k);
                        if (val != null) {
                            String[] parts = val.split("\\|");
                            String sessionId = parts.length > 0 ? parts[0] : "";
                            UUID documentId = null;
                            if (parts.length > 1 && !parts[1].isBlank()) {
                                documentId = UUID.fromString(parts[1]);
                            }
                            return new PresenceDetail(userId, sessionId, documentId);
                        }
                    } catch (Exception ignored) {}
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    public enum PresenceEventType {
        JOIN,
        LEAVE,
        VIEW,
        DOWNLOAD
    }

    public record PresenceDetail(UUID userId, String sessionId, UUID documentId) {}

    public record PresenceEvent(
            String type,
            UUID userId,
            String sessionId,
            UUID documentId,
            Instant timestamp
    ) {}
}
