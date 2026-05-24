package com.ivdr.domain.presence.controller;

import com.ivdr.common.response.ApiResponse;
import com.ivdr.domain.presence.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles real-time presence signalling for IVDR workspaces.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PresenceController {

    private static final String SESSION_ATTR_USER_ID = "userId";
    private final PresenceService presenceService;

    // =========================================================================
    // WebSocket / STOMP handlers
    // =========================================================================

    @MessageMapping("/workspace/{workspaceId}/join")
    public void handleJoin(
            @DestinationVariable UUID workspaceId,
            SimpMessageHeaderAccessor headerAccessor) {

        UUID userId = extractUserId(headerAccessor);
        if (userId == null) return;

        String sessionId = headerAccessor.getSessionId();
        log.info("WS JOIN  workspace={} user={} session={}", workspaceId, userId, sessionId);
        presenceService.userJoined(workspaceId, userId, sessionId);
    }

    @MessageMapping("/workspace/{workspaceId}/leave")
    public void handleLeave(
            @DestinationVariable UUID workspaceId,
            SimpMessageHeaderAccessor headerAccessor) {

        UUID userId = extractUserId(headerAccessor);
        if (userId == null) return;

        log.info("WS LEAVE workspace={} user={}", workspaceId, userId);
        presenceService.userLeft(workspaceId, userId);
    }

    @MessageMapping("/workspace/{workspaceId}/heartbeat")
    public void handleHeartbeat(
            @DestinationVariable UUID workspaceId,
            SimpMessageHeaderAccessor headerAccessor) {

        UUID userId = extractUserId(headerAccessor);
        if (userId == null) return;

        log.trace("WS HEARTBEAT workspace={} user={}", workspaceId, userId);
        presenceService.heartbeat(workspaceId, userId);
    }

    @MessageMapping("/workspace/{workspaceId}/view")
    public void handleView(
            @DestinationVariable UUID workspaceId,
            DocumentViewMessage message,
            SimpMessageHeaderAccessor headerAccessor) {

        UUID userId = extractUserId(headerAccessor);
        if (userId == null) return;

        presenceService.userViewedDocument(workspaceId, userId, message.documentId());
    }

    // =========================================================================
    // REST endpoints
    // =========================================================================

    @GetMapping("/workspaces/{workspaceId}/presence")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<UUID>>> getActiveUsers(
            @PathVariable UUID workspaceId) {

        List<UUID> activeUsers = presenceService.getActiveUsers(workspaceId);
        return ResponseEntity.ok(ApiResponse.ok("Active users retrieved", activeUsers));
    }

    @GetMapping("/workspaces/{workspaceId}/presence/details")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<PresenceService.PresenceDetail>>> getActivePresences(
            @PathVariable UUID workspaceId) {

        List<PresenceService.PresenceDetail> details = presenceService.getActivePresences(workspaceId);
        return ResponseEntity.ok(ApiResponse.ok("Presence details retrieved", details));
    }

    // =========================================================================
    // Inner Message Formats
    // =========================================================================

    public record DocumentViewMessage(UUID documentId) {}

    // =========================================================================
    // Private helpers
    // =========================================================================

    private UUID extractUserId(SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> attrs = headerAccessor.getSessionAttributes();
        if (attrs == null) {
            log.warn("STOMP message received with no session attributes — ignoring");
            return null;
        }

        Object raw = attrs.get(SESSION_ATTR_USER_ID);
        if (raw == null) {
            log.warn("STOMP session has no '{}' attribute — ignoring message", SESSION_ATTR_USER_ID);
            return null;
        }

        if (raw instanceof UUID uuid) {
            return uuid;
        }

        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException ex) {
            log.error("STOMP session has invalid '{}' attribute value '{}' — ignoring", SESSION_ATTR_USER_ID, raw);
            return null;
        }
    }
}
