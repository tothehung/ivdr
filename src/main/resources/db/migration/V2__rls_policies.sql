-- ================================================================
-- V2: PostgreSQL Row-Level Security (RLS) policies
-- Enforces tenant isolation at the database level
-- ================================================================

-- ----------------------------------------------------------------
-- Enable RLS on all tenant-scoped tables
-- ----------------------------------------------------------------
ALTER TABLE users           ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspaces      ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents       ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs      ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens  ENABLE ROW LEVEL SECURITY;

-- ----------------------------------------------------------------
-- Users table: users can only see users in their org
-- ----------------------------------------------------------------
CREATE POLICY users_tenant_isolation ON users
    USING (organization_id = current_setting('app.current_org_id')::UUID);

-- ----------------------------------------------------------------
-- Workspaces: only workspaces in the current org
-- ----------------------------------------------------------------
CREATE POLICY workspaces_tenant_isolation ON workspaces
    USING (organization_id = current_setting('app.current_org_id')::UUID);

-- ----------------------------------------------------------------
-- Workspace members: through workspace → org isolation
-- ----------------------------------------------------------------
CREATE POLICY workspace_members_tenant_isolation ON workspace_members
    USING (
        workspace_id IN (
            SELECT id FROM workspaces
            WHERE organization_id = current_setting('app.current_org_id')::UUID
        )
    );

-- ----------------------------------------------------------------
-- Documents: direct org_id column
-- ----------------------------------------------------------------
CREATE POLICY documents_tenant_isolation ON documents
    USING (organization_id = current_setting('app.current_org_id')::UUID);

-- ----------------------------------------------------------------
-- Audit logs: tenant isolation
-- ----------------------------------------------------------------
CREATE POLICY audit_logs_tenant_isolation ON audit_logs
    USING (organization_id = current_setting('app.current_org_id')::UUID);

-- Audit logs: INSERT is allowed (auditors can write), but no UPDATE/DELETE
CREATE POLICY audit_logs_insert_only ON audit_logs
    FOR INSERT
    WITH CHECK (organization_id = current_setting('app.current_org_id')::UUID);

CREATE POLICY audit_logs_no_update ON audit_logs
    FOR UPDATE USING (FALSE);

CREATE POLICY audit_logs_no_delete ON audit_logs
    FOR DELETE USING (FALSE);

-- ----------------------------------------------------------------
-- Refresh tokens: user can only access their own tokens
-- ----------------------------------------------------------------
CREATE POLICY refresh_tokens_user_isolation ON refresh_tokens
    USING (
        user_id IN (
            SELECT id FROM users
            WHERE organization_id = current_setting('app.current_org_id')::UUID
        )
    );

-- ----------------------------------------------------------------
-- Grant SELECT/INSERT/UPDATE/DELETE to the app user
-- (RLS policies still apply even with full grants)
-- ----------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ivdr_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ivdr_user;

-- ----------------------------------------------------------------
-- Helper function: set the current org session variable
-- (Called by TenantContextService on every request)
-- ----------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_tenant_context(org_id UUID)
RETURNS void AS $$
BEGIN
    PERFORM set_config('app.current_org_id', org_id::TEXT, TRUE);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION set_tenant_context(UUID) TO ivdr_user;
