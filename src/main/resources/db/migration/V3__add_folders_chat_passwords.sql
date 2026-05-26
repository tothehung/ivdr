-- ================================================================
-- V3: Add Folders, Chat Messages, and Document Passwords
-- ================================================================

-- ----------------------------------------------------------------
-- Folders
-- ----------------------------------------------------------------
CREATE TABLE folders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    parent_id       UUID REFERENCES folders(id) ON DELETE CASCADE,
    created_by      UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_folders_workspace ON folders(workspace_id);
CREATE INDEX idx_folders_parent ON folders(parent_id);

-- ----------------------------------------------------------------
-- Modify documents to support folders and password protection
-- ----------------------------------------------------------------
ALTER TABLE documents ADD COLUMN folder_id UUID REFERENCES folders(id) ON DELETE SET NULL;
ALTER TABLE documents ADD COLUMN password_hash VARCHAR(255);

CREATE INDEX idx_documents_folder ON documents(folder_id);

-- ----------------------------------------------------------------
-- Chat Messages (Workspace group chat and personal messages)
-- ----------------------------------------------------------------
CREATE TABLE chat_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID REFERENCES workspaces(id) ON DELETE CASCADE,
    sender_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_id    UUID REFERENCES users(id) ON DELETE CASCADE, -- Null for workspace channel chat
    message_text    TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_messages_workspace ON chat_messages(workspace_id);
CREATE INDEX idx_chat_messages_sender ON chat_messages(sender_id);
CREATE INDEX idx_chat_messages_recipient ON chat_messages(recipient_id);
CREATE INDEX idx_chat_messages_created ON chat_messages(created_at ASC);

-- ----------------------------------------------------------------
-- Trigger for folders updated_at
-- ----------------------------------------------------------------
CREATE TRIGGER trg_folders_updated_at
    BEFORE UPDATE ON folders
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
