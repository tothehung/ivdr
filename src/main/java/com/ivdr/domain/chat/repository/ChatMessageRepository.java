package com.ivdr.domain.chat.repository;

import com.ivdr.domain.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /**
     * Find group channel chat messages for a workspace, ordered by creation time.
     */
    List<ChatMessage> findByWorkspaceIdAndRecipientIdIsNullOrderByCreatedAtAsc(UUID workspaceId);

    /**
     * Find direct chat history between two users, ordered by creation time.
     */
    @Query("SELECT m FROM ChatMessage m WHERE " +
           "m.workspaceId = :workspaceId AND " +
           "((m.senderId = :user1 AND m.recipientId = :user2) OR " +
           " (m.senderId = :user2 AND m.recipientId = :user1)) " +
           "ORDER BY m.createdAt ASC")
    List<ChatMessage> findDirectChatHistory(@Param("workspaceId") UUID workspaceId,
                                            @Param("user1") UUID user1,
                                            @Param("user2") UUID user2);
}
