package com.ivdr.domain.chat.controller;

import com.ivdr.domain.chat.dto.ChatDtos.ChatMessageRequest;
import com.ivdr.domain.chat.dto.ChatDtos.ChatMessageResponse;
import com.ivdr.domain.chat.service.ChatService;
import com.ivdr.security.UserPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/workspaces/{workspaceId}/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "Workspace real-time chat history query management")
public class ChatRestController {

    private final ChatService chatService;

    /**
     * Gets workspace group channel chat history.
     */
    @GetMapping("/history")
    public ResponseEntity<List<ChatMessageResponse>> getGroupChatHistory(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Fetching group chat history — workspaceId={} user={}", workspaceId, principal.userId());
        List<ChatMessageResponse> history = chatService.getGroupChatHistory(workspaceId, principal);
        return ResponseEntity.ok(history);
    }

    /**
     * Gets private direct messaging history between the authenticated user and another member in a workspace.
     */
    @GetMapping("/direct/{otherUserId}")
    public ResponseEntity<List<ChatMessageResponse>> getDirectChatHistory(
            @PathVariable UUID workspaceId,
            @PathVariable UUID otherUserId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Fetching direct chat history — workspaceId={} caller={} peer={}",
                workspaceId, principal.userId(), otherUserId);
        List<ChatMessageResponse> history = chatService.getDirectChatHistory(workspaceId, otherUserId, principal);
        return ResponseEntity.ok(history);
    }

    /**
     * REST endpoint to send a chat message (group or direct).
     */
    @PostMapping("/send")
    public ResponseEntity<ChatMessageResponse> sendChatMessage(
            @PathVariable UUID workspaceId,
            @RequestBody ChatMessageRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("REST chat message received from user {}: {}", principal.userId(), request.messageText());
        ChatMessageRequest scopedRequest = new ChatMessageRequest(
                workspaceId,
                request.recipientId(),
                request.messageText()
        );
        ChatMessageResponse response = chatService.saveAndRouteMessage(principal.userId(), scopedRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Gets the list of sender user IDs who have sent unread direct messages to the authenticated user.
     */
    @GetMapping("/unread")
    public ResponseEntity<List<UUID>> getUnreadSenders(
            @PathVariable UUID workspaceId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Fetching unread DM senders — workspaceId={} caller={}", workspaceId, principal.userId());
        List<UUID> unread = chatService.getUnreadSenders(workspaceId, principal.userId());
        return ResponseEntity.ok(unread);
    }

    /**
     * Marks all unread direct messages from another member in a workspace as read.
     */
    @PostMapping("/direct/{otherUserId}/read")
    public ResponseEntity<Void> markDirectMessagesAsRead(
            @PathVariable UUID workspaceId,
            @PathVariable UUID otherUserId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        log.debug("Marking messages from other user as read — workspaceId={} caller={} sender={}",
                workspaceId, principal.userId(), otherUserId);
        chatService.markDirectMessagesAsRead(workspaceId, otherUserId, principal.userId());
        return ResponseEntity.ok().build();
    }
}
