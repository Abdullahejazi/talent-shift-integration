CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE user_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_auth_sessions_user ON auth_sessions(user_id);
CREATE INDEX idx_auth_sessions_expiry ON auth_sessions(expires_at);
CREATE TABLE jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(60) NOT NULL,
    external_id VARCHAR(180) NOT NULL,
    title VARCHAR(240) NOT NULL,
    company VARCHAR(200) NOT NULL,
    location VARCHAR(200),
    employment_type VARCHAR(80),
    remote BOOLEAN NOT NULL DEFAULT false,
    salary VARCHAR(160),
    category VARCHAR(120),
    description TEXT,
    requirements TEXT,
    apply_url TEXT NOT NULL,
    source_url TEXT,
    posted_at TIMESTAMPTZ,
    collected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    UNIQUE (source, external_id)
);
CREATE INDEX idx_jobs_status_posted ON jobs(status, posted_at DESC);
CREATE INDEX idx_jobs_source ON jobs(source);
CREATE INDEX idx_jobs_remote ON jobs(remote);
CREATE INDEX idx_jobs_title_search ON jobs USING gin (to_tsvector('simple', title || ' ' || company || ' ' || COALESCE(description, '')));
CREATE TABLE candidate_profiles (
    user_id UUID PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE,
    full_name VARCHAR(160),
    email VARCHAR(320),
    phone VARCHAR(80),
    location VARCHAR(180),
    headline VARCHAR(240),
    summary TEXT,
    skills_json TEXT NOT NULL DEFAULT '[]',
    cv_filename VARCHAR(260),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE cv_uploads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    original_filename VARCHAR(260) NOT NULL,
    stored_filename VARCHAR(260) NOT NULL,
    content_type VARCHAR(120),
    size_bytes BIGINT NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE job_collection_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    inserted_count INTEGER NOT NULL DEFAULT 0,
    updated_count INTEGER NOT NULL DEFAULT 0,
    failed_sources TEXT NOT NULL DEFAULT '',
    status VARCHAR(30) NOT NULL
);
