-- ================================================================
-- V4: Cross-tenant workspace collaboration support
-- ================================================================

-- Create secure helper function to check workspace membership (bypassing RLS)
CREATE OR REPLACE FUNCTION is_workspace_member(ws_id UUID, u_id UUID)
RETURNS boolean AS $$
BEGIN
    IF u_id IS NULL OR ws_id IS NULL THEN
        RETURN FALSE;
    END IF;
    RETURN EXISTS (
        SELECT 1 FROM workspace_members
        WHERE workspace_id = ws_id AND user_id = u_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION is_workspace_member(UUID, UUID) TO ivdr_user;

-- Create secure helper function to check if two users share any workspace (bypassing RLS)
CREATE OR REPLACE FUNCTION share_workspace(u1_id UUID, u2_id UUID)
RETURNS boolean AS $$
BEGIN
    IF u1_id IS NULL OR u2_id IS NULL THEN
        RETURN FALSE;
    END IF;
    RETURN EXISTS (
        SELECT 1 FROM workspace_members wm1
        JOIN workspace_members wm2 ON wm1.workspace_id = wm2.workspace_id
        WHERE wm1.user_id = u1_id AND wm2.user_id = u2_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION share_workspace(UUID, UUID) TO ivdr_user;

-- Create helper function to set both tenant context (org_id) and user context (user_id)
CREATE OR REPLACE FUNCTION set_session_context(org_id UUID, user_id UUID)
RETURNS void AS $$
BEGIN
    PERFORM set_config('app.current_org_id', org_id::TEXT, TRUE);
    PERFORM set_config('app.current_user_id', user_id::TEXT, TRUE);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

GRANT EXECUTE ON FUNCTION set_session_context(UUID, UUID) TO ivdr_user;

-- Drop old strict tenant-only policies
DROP POLICY IF EXISTS users_tenant_isolation ON users;
DROP POLICY IF EXISTS workspaces_tenant_isolation ON workspaces;
DROP POLICY IF EXISTS workspace_members_tenant_isolation ON workspace_members;
DROP POLICY IF EXISTS documents_tenant_isolation ON documents;

-- Create new policies allowing cross-tenant workspace membership
CREATE POLICY users_tenant_isolation ON users
    USING (
        organization_id = NULLIF(current_setting('app.current_org_id', true), '')::UUID
        OR share_workspace(id, NULLIF(current_setting('app.current_user_id', true), '')::UUID)
    );

CREATE POLICY workspaces_tenant_isolation ON workspaces
    USING (
        organization_id = NULLIF(current_setting('app.current_org_id', true), '')::UUID
        OR is_workspace_member(id, NULLIF(current_setting('app.current_user_id', true), '')::UUID)
    );

CREATE POLICY workspace_members_tenant_isolation ON workspace_members
    USING (
        workspace_id IN (
            SELECT id FROM workspaces
            WHERE organization_id = NULLIF(current_setting('app.current_org_id', true), '')::UUID
        )
        OR is_workspace_member(workspace_id, NULLIF(current_setting('app.current_user_id', true), '')::UUID)
    );

CREATE POLICY documents_tenant_isolation ON documents
    USING (
        organization_id = NULLIF(current_setting('app.current_org_id', true), '')::UUID
        OR is_workspace_member(workspace_id, NULLIF(current_setting('app.current_user_id', true), '')::UUID)
    );
