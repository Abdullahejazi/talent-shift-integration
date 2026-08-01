CREATE TABLE job_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID REFERENCES companies(id) ON DELETE CASCADE,
    company_name VARCHAR(200) NOT NULL,
    careers_url TEXT NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    ats_provider VARCHAR(40),
    board_identifier VARCHAR(180),
    country CHAR(2) NOT NULL DEFAULT 'SA',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    permission_status VARCHAR(30) NOT NULL DEFAULT 'PUBLIC_ALLOWED',
    refresh_interval_minutes INTEGER NOT NULL DEFAULT 60,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sync_cursor TEXT,
    etag VARCHAR(500),
    last_modified VARCHAR(200),
    response_checksum CHAR(64),
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    circuit_open_until TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT job_sources_type_check CHECK (source_type IN
        ('GREENHOUSE','LEVER','ASHBY','SMARTRECRUITERS','JSON_LD','RSS','GENERIC_HTML','BROWSER_FALLBACK','WORKDAY','WORKABLE','BREEZY','RECRUITEE','LINKEDIN')),
    CONSTRAINT job_sources_permission_check CHECK (permission_status IN
        ('PUBLIC_ALLOWED','API_LICENSED','MANUAL_APPROVAL','BLOCKED','UNKNOWN')),
    CONSTRAINT job_sources_refresh_check CHECK (refresh_interval_minutes BETWEEN 5 AND 10080),
    CONSTRAINT job_sources_failures_check CHECK (consecutive_failures >= 0)
);

CREATE UNIQUE INDEX uq_job_sources_careers_url ON job_sources(lower(careers_url));
CREATE INDEX idx_job_sources_due ON job_sources(enabled, next_retry_at);
CREATE INDEX idx_job_sources_provider ON job_sources(ats_provider, enabled);

INSERT INTO job_sources(company_id, company_name, careers_url, source_type, ats_provider, board_identifier,
                        permission_status, refresh_interval_minutes)
SELECT c.id, c.name, c.careers_url,
       CASE
           WHEN lower(c.careers_url) LIKE '%jobs.lever.co/%' THEN 'LEVER'
           WHEN lower(c.careers_url) LIKE '%greenhouse.io/%' THEN 'GREENHOUSE'
           WHEN lower(c.careers_url) LIKE '%ashbyhq.com/%' THEN 'ASHBY'
           WHEN lower(c.careers_url) LIKE '%smartrecruiters.com/%' THEN 'SMARTRECRUITERS'
           ELSE 'JSON_LD'
       END,
       CASE
           WHEN lower(c.careers_url) LIKE '%jobs.lever.co/%' THEN 'LEVER'
           WHEN lower(c.careers_url) LIKE '%greenhouse.io/%' THEN 'GREENHOUSE'
           WHEN lower(c.careers_url) LIKE '%ashbyhq.com/%' THEN 'ASHBY'
           WHEN lower(c.careers_url) LIKE '%smartrecruiters.com/%' THEN 'SMARTRECRUITERS'
       END,
       CASE
           WHEN lower(c.careers_url) LIKE '%jobs.lever.co/%'
             OR lower(c.careers_url) LIKE '%greenhouse.io/%'
             OR lower(c.careers_url) LIKE '%ashbyhq.com/%'
             OR lower(c.careers_url) LIKE '%smartrecruiters.com/%'
           THEN split_part(trim(both '/' from regexp_replace(c.careers_url, '^https?://[^/]+', '')), '/', 1)
       END,
       CASE WHEN c.source_type='AGGREGATOR_API' THEN 'API_LICENSED' ELSE 'PUBLIC_ALLOWED' END,
       CASE WHEN c.source_type='PUBLIC_ATS_API' THEN 30 ELSE 180 END
FROM companies c
WHERE c.active=true AND c.automated=true
ON CONFLICT DO NOTHING;

ALTER TABLE jobs
    ADD COLUMN source_id UUID REFERENCES job_sources(id) ON DELETE SET NULL,
    ADD COLUMN canonical_application_url TEXT,
    ADD COLUMN content_fingerprint CHAR(64),
    ADD COLUMN source_quality SMALLINT NOT NULL DEFAULT 5,
    ADD COLUMN is_direct_employer BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN original_source_url TEXT,
    ADD COLUMN first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN last_verified_at TIMESTAMPTZ,
    ADD COLUMN missing_observations INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN collection_status VARCHAR(30) NOT NULL DEFAULT 'COMPLETE',
    ADD COLUMN enrichment_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN enrichment_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN normalized_category VARCHAR(120),
    ADD COLUMN experience_level VARCHAR(60),
    ADD COLUMN summary_en TEXT,
    ADD COLUMN summary_ar TEXT,
    ADD COLUMN skills_json JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE jobs SET canonical_application_url=apply_url, original_source_url=source_url,
    content_fingerprint=encode(digest(lower(coalesce(description,'')), 'sha256'),'hex'),
    source_quality=CASE WHEN source='SEED_COMPANIES' THEN 1 WHEN source IN ('JOOBLE','ARBEITNOW','REMOTIVE') THEN 3 ELSE 5 END,
    is_direct_employer=(source='SEED_COMPANIES'), last_verified_at=last_seen_at;

ALTER TABLE jobs
    ALTER COLUMN canonical_application_url SET NOT NULL,
    ALTER COLUMN content_fingerprint SET NOT NULL,
    ADD CONSTRAINT jobs_source_quality_check CHECK (source_quality BETWEEN 1 AND 5),
    ADD CONSTRAINT jobs_missing_observations_check CHECK (missing_observations >= 0),
    ADD CONSTRAINT jobs_enrichment_attempts_check CHECK (enrichment_attempts >= 0),
    ADD CONSTRAINT jobs_collection_status_check CHECK (collection_status IN ('COMPLETE','PARTIAL','FAILED')),
    ADD CONSTRAINT jobs_enrichment_status_check CHECK (enrichment_status IN ('PENDING','PROCESSING','COMPLETE','FAILED','NOT_REQUIRED'));

CREATE UNIQUE INDEX uq_jobs_source_id_external ON jobs(source_id, external_id) WHERE source_id IS NOT NULL;
CREATE INDEX idx_jobs_canonical_application_url ON jobs(canonical_application_url);
CREATE INDEX IF NOT EXISTS idx_jobs_status_posted ON jobs(status, posted_at DESC);
CREATE INDEX idx_jobs_company_name ON jobs(lower(company));
CREATE INDEX idx_jobs_country_location ON jobs(country_code, location);
CREATE INDEX idx_jobs_last_verified ON jobs(last_verified_at);
CREATE INDEX idx_jobs_enrichment_pending ON jobs(enrichment_status, collected_at) WHERE enrichment_status='PENDING';

CREATE TABLE job_source_fetches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id UUID NOT NULL REFERENCES job_sources(id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    http_status INTEGER,
    result_count INTEGER NOT NULL DEFAULT 0,
    response_bytes BIGINT NOT NULL DEFAULT 0,
    checksum CHAR(64),
    outcome VARCHAR(30) NOT NULL,
    error_code VARCHAR(80),
    CONSTRAINT job_source_fetches_outcome_check CHECK (outcome IN ('SUCCESS','NOT_MODIFIED','RETRYABLE_FAILURE','PERMANENT_FAILURE','BLOCKED'))
);
CREATE INDEX idx_job_source_fetches_source_time ON job_source_fetches(source_id, started_at DESC);
