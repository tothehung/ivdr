-- ================================================================
-- PostgreSQL initialization script
-- Runs once when the container first starts
-- ================================================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";   -- for full-text search

-- Create application schema (Flyway will manage tables)
-- This file only sets up extensions and roles that need superuser

-- Row-Level Security will be configured in V2 Flyway migration
-- but we ensure the app user has the right permissions
GRANT ALL PRIVILEGES ON DATABASE ivdr_db TO ivdr_user;
