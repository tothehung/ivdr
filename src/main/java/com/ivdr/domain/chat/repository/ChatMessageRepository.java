package com.ivdr.domain.chat.repository;

import com.ivdr.domain.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Marks all unread messages from a sender to a recipient in a workspace as read.
     */
    @Modifying
    @Query("UPDATE ChatMessage m SET m.isRead = true " +
           "WHERE m.workspaceId = :workspaceId " +
           "AND m.senderId = :senderId " +
           "AND m.recipientId = :recipientId " +
           "AND m.isRead = false")
    int markMessagesAsRead(@Param("workspaceId") UUID workspaceId,
                           @Param("senderId") UUID senderId,
                           @Param("recipientId") UUID recipientId);

    /**
     * Finds the list of sender IDs who have sent unread direct messages to the recipient in a workspace.
     */
    @Query("SELECT DISTINCT m.senderId FROM ChatMessage m " +
           "WHERE m.workspaceId = :workspaceId " +
           "AND m.recipientId = :recipientId " +
           "AND m.isRead = false")
    List<UUID> findUnreadSenders(@Param("workspaceId") UUID workspaceId,
                                 @Param("recipientId") UUID recipientId);
}
