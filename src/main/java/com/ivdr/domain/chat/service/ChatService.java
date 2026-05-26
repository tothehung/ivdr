package com.ivdr.domain.chat.service;

import com.ivdr.common.exception.ApiException;
import com.ivdr.domain.chat.dto.ChatDtos.*;
import com.ivdr.domain.chat.entity.ChatMessage;
import com.ivdr.domain.chat.repository.ChatMessageRepository;
import com.ivdr.domain.workspace.repository.WorkspaceMemberRepository;
import com.ivdr.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMessageRepository chatMessageRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Persists a new chat message and routes it to the correct STOMP destination.
     */
    @Transactional
    public ChatMessageResponse saveAndRouteMessage(UUID senderId, ChatMessageRequest req) {
        if (req.workspaceId() == null) {
            throw ApiException.badRequest("Workspace ID is required for chat messages.");
        }

        boolean isMember = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(req.workspaceId(), senderId)
                .isPresent();

        if (!isMember) {
            throw ApiException.forbidden("You are not a member of this workspace.");
        }

        ChatMessage message = ChatMessage.builder()
                .workspaceId(req.workspaceId())
                .senderId(senderId)
                .recipientId(req.recipientId())
                .messageText(req.messageText())
                .build();

        ChatMessage saved = chatMessageRepository.save(message);
        ChatMessageResponse response = toResponse(saved);

        if (req.recipientId() == null) {
            // Workspace group channel broadcast
            String destination = "/topic/chat/" + req.workspaceId();
            log.debug("Broadcasting group chat message to destination: {}", destination);
            messagingTemplate.convertAndSend(destination, response);
        } else {
            // Direct message: broadcast to workspace DM topic
            // All workspace members receive this, but frontend filters by senderId/recipientId
            String dmDestination = "/topic/chat.dm/" + req.workspaceId();
            log.debug("Broadcasting DM to {}: sender={}, recipient={}", dmDestination, senderId, req.recipientId());
            messagingTemplate.convertAndSend(dmDestination, response);
        }

        return response;
    }

    /**
     * Retrieves the history of a workspace group chat channel.
     */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getGroupChatHistory(UUID workspaceId, UserPrincipal principal) {
        boolean isMember = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, principal.userId())
                .isPresent();
        if (!isMember) {
            throw ApiException.forbidden("You are not a member of this workspace.");
        }

        return chatMessageRepository
                .findByWorkspaceIdAndRecipientIdIsNullOrderByCreatedAtAsc(workspaceId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves direct chat history between the principal and another user in a given workspace.
     */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getDirectChatHistory(UUID workspaceId, UUID otherUserId, UserPrincipal principal) {
        boolean callerIsMember = workspaceMemberRepository
                .findByWorkspaceIdAndUserId(workspaceId, principal.userId())
                .isPresent();
        if (!callerIsMember) {
            throw ApiException.forbidden("You are not a member of this workspace.");
        }

        return chatMessageRepository
                .findDirectChatHistory(workspaceId, principal.userId(), otherUserId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private ChatMessageResponse toResponse(ChatMessage msg) {
        return new ChatMessageResponse(
                msg.getId(),
                msg.getWorkspaceId(),
                msg.getSenderId(),
                msg.getRecipientId(),
                msg.getMessageText(),
                msg.getCreatedAt()
        );
    }
}
