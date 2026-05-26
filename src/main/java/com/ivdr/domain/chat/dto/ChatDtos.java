package com.ivdr.domain.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public final class ChatDtos {
    private ChatDtos() {}

    public record ChatMessageRequest(
            UUID workspaceId,
            UUID recipientId, // null for group chat, set for direct message
            String messageText
    ) {}

    public record ChatMessageResponse(
            UUID id,
            UUID workspaceId,
            UUID senderId,
            UUID recipientId,
            String messageText,
            LocalDateTime createdAt,
            boolean isRead
    ) {}

    public record ChatReadReceipt(
            UUID workspaceId,
            UUID readerId,
            UUID senderId
    ) {}
}
