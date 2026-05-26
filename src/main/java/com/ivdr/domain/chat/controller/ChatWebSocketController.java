package com.ivdr.domain.chat.controller;

import com.ivdr.domain.chat.dto.ChatDtos.ChatMessageRequest;
import com.ivdr.domain.chat.service.ChatService;
import com.ivdr.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final ChatService chatService;

    /**
     * Handles chat messages sent to /app/chat.send.
     * Extracts authenticated sender ID from WebSocket principal session context.
     */
    @MessageMapping("/chat.send")
    public void sendChatMessage(ChatMessageRequest request, Principal principal) {
        if (principal == null) {
            log.warn("Attempt to send chat message over WebSocket without principal credentials");
            return;
        }

        UUID senderId = null;
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            if (auth.getPrincipal() instanceof UserPrincipal userPrincipal) {
                senderId = userPrincipal.userId();
            }
        }

        if (senderId == null) {
            log.warn("Could not extract user UUID from WebSocket UsernamePasswordAuthenticationToken");
            return;
        }

        log.debug("WebSocket chat message from user {} received: {}", senderId, request.messageText());
        chatService.saveAndRouteMessage(senderId, request);
    }
}
