CREATE TABLE ai_discovery_searches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query_hash CHAR(64) NOT NULL UNIQUE,
    query_text TEXT NOT NULL,
    search_kind VARCHAR(30) NOT NULL,
    searched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    result_count INTEGER NOT NULL DEFAULT 0,
    jobs_accepted INTEGER NOT NULL DEFAULT 0,
    seeds_promoted INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ai_discovery_search_kind_check CHECK (search_kind IN ('JOBS','PLATFORMS','EVENTS','COMPANIES','SEEDS'))
);

CREATE INDEX idx_ai_discovery_searches_time ON ai_discovery_searches(searched_at DESC);

CREATE TABLE ai_discovery_targets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_key VARCHAR(500) NOT NULL UNIQUE,
    discovered_url TEXT NOT NULL,
    host VARCHAR(255) NOT NULL,
    target_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DISCOVERED',
    source_id UUID REFERENCES job_sources(id) ON DELETE SET NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    seen_count INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT ai_discovery_target_type_check CHECK (target_type IN ('JOB','PLATFORM','EVENT','COMPANY','ATS_BOARD')),
    CONSTRAINT ai_discovery_target_status_check CHECK (status IN ('DISCOVERED','VERIFIED','PROMOTED','REJECTED'))
);

CREATE INDEX idx_ai_discovery_targets_status ON ai_discovery_targets(status,last_seen_at DESC);

-- All approved database seeds are due once per hour. The scheduler polls more
-- frequently, but conditional requests prevent unnecessary downloads.
UPDATE job_sources
SET refresh_interval_minutes=60,
    next_retry_at=LEAST(next_retry_at, now() + interval '1 hour'),
    updated_at=now()
WHERE permission_status IN ('PUBLIC_ALLOWED','API_LICENSED');
