package com.ivdr.domain.presence.service;

import com.ivdr.domain.workspace.entity.WorkspaceMember;
import com.ivdr.domain.workspace.repository.WorkspaceMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages real-time user presence for IVDR workspaces with multi-session support.
 *
 * <p>Presence state is persisted in Redis per session to avoid conflicts when a user
 * opens multiple tabs or browsers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final WorkspaceMemberRepository workspaceMemberRepository;

    @Value("${app.presence.session-ttl-seconds:30}")
    private int ttl;

    private String userPresencePattern(UUID workspaceId, UUID userId) {
        return "presence:" + workspaceId + ":" + userId + ":*";
    }

    private String sessionPresenceKey(UUID workspaceId, UUID userId, String sessionId) {
        return "presence:" + workspaceId + ":" + userId + ":" + (sessionId != null ? sessionId : "unknown");
    }

    private String lastSeenKey(UUID workspaceId, UUID userId) {
        return "presence:last_seen:" + workspaceId + ":" + userId;
    }

    private String presenceTopic(UUID workspaceId) {
        return "/topic/presence/" + workspaceId;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void userJoined(UUID workspaceId, UUID userId, String sessionId) {
        String key = sessionPresenceKey(workspaceId, userId, sessionId);
        long now = Instant.now().toEpochMilli();
        String val = "idle||" + now;
        redisTemplate.opsForValue().set(key, val, ttl, TimeUnit.SECONDS);

        // Store last seen timestamp permanently (7 days TTL)
        redisTemplate.opsForValue().set(lastSeenKey(workspaceId, userId), String.valueOf(now), 7, TimeUnit.DAYS);

        log.debug("User {} (session {}) joined workspace {}", userId, sessionId, workspaceId);

        PresenceEvent event = new PresenceEvent(
                PresenceEventType.JOIN.name(),
                userId,
                sessionId,
                "idle",
                "",
                now
        );
        messagingTemplate.convertAndSend(presenceTopic(workspaceId), event);
    }

    public void updateActivity(UUID workspaceId, UUID userId, String sessionId, String activityType, String activityDetail) {
        String key = sessionPresenceKey(workspaceId, userId, sessionId);
        long now = Instant.now().toEpochMilli();
        String val = activityType + "|" + (activityDetail != null ? activityDetail : "") + "|" + now;
        
        redisTemplate.opsForValue().set(key, val, ttl, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(lastSeenKey(workspaceId, userId), String.valueOf(now), 7, TimeUnit.DAYS);

        log.debug("User {} (session {}) activity in {}: {} -> {}", userId, sessionId, workspaceId, activityType, activityDetail);

        PresenceEvent event = new PresenceEvent(
                PresenceEventType.VIEW.name(),
                userId,
                sessionId,
                activityType,
                activityDetail != null ? activityDetail : "",
                now
        );
        messagingTemplate.convertAndSend(presenceTopic(workspaceId), event);
    }

    public void userViewedDocument(UUID workspaceId, UUID userId, String sessionId, UUID documentId) {
        updateActivity(workspaceId, userId, sessionId, "viewing", documentId != null ? documentId.toString() : "");
    }

    public void userLeft(UUID workspaceId, UUID userId, String sessionId) {
        String key = sessionPresenceKey(workspaceId, userId, sessionId);
        redisTemplate.delete(key);

        log.debug("User {} (session {}) left workspace {}", userId, sessionId, workspaceId);

        // Check if there are other sessions still active for this user
        Set<String> remainingSessions = redisTemplate.keys(userPresencePattern(workspaceId, userId));
        boolean stillOnline = remainingSessions != null && !remainingSessions.isEmpty();

        // If no sessions remain, broadcast LEAVE
        if (!stillOnline) {
            PresenceEvent event = new PresenceEvent(
                    PresenceEventType.LEAVE.name(),
                    userId,
                    sessionId,
                    "offline",
                    "",
                    Instant.now().toEpochMilli()
            );
            messagingTemplate.convertAndSend(presenceTopic(workspaceId), event);
        } else {
            // Broadcast VIEW with current aggregated state
            heartbeat(workspaceId, userId, remainingSessions.iterator().next().split(":")[3]);
        }
    }

    public void heartbeat(UUID workspaceId, UUID userId, String sessionId) {
        String key = sessionPresenceKey(workspaceId, userId, sessionId);
        String val = (String) redisTemplate.opsForValue().get(key);
        long now = Instant.now().toEpochMilli();

        String activityType = "idle";
        String activityDetail = "";

        if (val != null) {
            String[] parts = val.split("\\|");
            if (parts.length > 0) activityType = parts[0];
            if (parts.length > 1) activityDetail = parts[1];
        }

        String newVal = activityType + "|" + activityDetail + "|" + now;
        redisTemplate.opsForValue().set(key, newVal, ttl, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(lastSeenKey(workspaceId, userId), String.valueOf(now), 7, TimeUnit.DAYS);

        // Broadcast alive heartbeat
        PresenceEvent event = new PresenceEvent(
                "HEARTBEAT",
                userId,
                sessionId,
                activityType,
                activityDetail,
                now
        );
        messagingTemplate.convertAndSend(presenceTopic(workspaceId), event);
    }

    public List<UUID> getActiveUsers(UUID workspaceId) {
        String pattern = "presence:" + workspaceId + ":*:*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        return keys.stream()
                .filter(Objects::nonNull)
                .map(k -> {
                    String[] parts = k.split(":");
                    if (parts.length > 2) {
                        try {
                            return UUID.fromString(parts[2]);
                        } catch (IllegalArgumentException ex) {
                            return null;
                        }
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    public List<PresenceDetail> getActivePresences(UUID workspaceId) {
        List<WorkspaceMember> members = workspaceMemberRepository.findAllByWorkspaceId(workspaceId);
        
        return members.stream()
                .map(member -> {
                    UUID userId = member.getUserId();
                    Set<String> sessionKeys = redisTemplate.keys(userPresencePattern(workspaceId, userId));
                    
                    if (sessionKeys != null && !sessionKeys.isEmpty()) {
                        // Aggregate multi-session status
                        String aggregatedActivity = "idle";
                        String aggregatedDetail = "";
                        long maxLastActive = 0;
                        String activeSessionId = "unknown";

                        for (String key : sessionKeys) {
                            String val = (String) redisTemplate.opsForValue().get(key);
                            if (val != null) {
                                try {
                                    String[] parts = val.split("\\|");
                                    String activity = parts.length > 0 ? parts[0] : "idle";
                                    String detail = parts.length > 1 ? parts[1] : "";
                                    long ts = parts.length > 2 ? Long.parseLong(parts[2]) : Instant.now().toEpochMilli();
                                    
                                    if (ts > maxLastActive) {
                                        maxLastActive = ts;
                                        // Priority of activities: editing > uploading > viewing > idle
                                        if (aggregatedActivity.equals("idle") || 
                                           (aggregatedActivity.equals("viewing") && (activity.equals("editing") || activity.equals("uploading"))) ||
                                           (aggregatedActivity.equals("uploading") && activity.equals("editing"))) {
                                            aggregatedActivity = activity;
                                            aggregatedDetail = detail;
                                        }
                                    }
                                    String[] keyParts = key.split(":");
                                    if (keyParts.length > 3) activeSessionId = keyParts[3];
                                } catch (Exception ignored) {}
                            }
                        }

                        if (maxLastActive == 0) maxLastActive = Instant.now().toEpochMilli();
                        return new PresenceDetail(userId, activeSessionId, "online", aggregatedActivity, aggregatedDetail, maxLastActive);
                    }
                    
                    // Offline member: read last seen timestamp
                    String lastSeenVal = (String) redisTemplate.opsForValue().get(lastSeenKey(workspaceId, userId));
                    long lastActive = 0;
                    if (lastSeenVal != null) {
                        try {
                            lastActive = Long.parseLong(lastSeenVal);
                        } catch (NumberFormatException ignored) {}
                    }
                    
                    if (lastActive == 0) {
                        // fallback to joined date
                        lastActive = member.getJoinedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    }
                    
                    return new PresenceDetail(userId, "", "offline", "offline", "", lastActive);
                })
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

    public record PresenceDetail(
            UUID userId,
            String sessionId,
            String status,
            String activity,
            String activityDetail,
            Long lastActiveAt
    ) {}

    public record PresenceEvent(
            String type,
            UUID userId,
            String sessionId,
            String activity,
            String activityDetail,
            Long timestamp
    ) {
        // Constructor for backward compatibility with DocumentService
        public PresenceEvent(String type, UUID userId, String sessionId, UUID documentId, Instant timestamp) {
            this(type, userId, sessionId, "DOWNLOAD", documentId != null ? documentId.toString() : "", timestamp.toEpochMilli());
        }
    }
}
