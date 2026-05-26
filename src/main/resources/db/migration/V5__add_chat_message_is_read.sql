-- ================================================================
-- V5: Add is_read column to chat_messages
-- ================================================================

ALTER TABLE chat_messages ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT FALSE;
