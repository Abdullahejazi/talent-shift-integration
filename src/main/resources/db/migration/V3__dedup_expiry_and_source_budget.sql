ALTER TABLE jobs
    ADD COLUMN dedup_key CHAR(64),
    ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE jobs
SET dedup_key = encode(digest(
        lower(trim(title)) || '|' || lower(trim(company)) || '|' || lower(trim(COALESCE(location, ''))),
        'sha256'), 'hex'),
    expires_at = COALESCE(posted_at, collected_at) + INTERVAL '45 days';

-- Earlier releases allowed worldwide remote listings. Retire those records so the
-- active inventory is strictly Saudi Arabia even when upgrading an existing database.
UPDATE jobs
SET saudi_relevant = FALSE, status = 'OUT_OF_SCOPE'
WHERE COALESCE(upper(country_code), '') <> 'SA'
  AND NOT (lower(COALESCE(location, '')) LIKE ANY (ARRAY[
    '%saudi%', '%riyadh%', '%jeddah%', '%makkah%', '%mecca%', '%madinah%', '%medina%',
    '%dammam%', '%khobar%', '%dhahran%', '%tabuk%', '%jubail%', '%yanbu%', '%abha%',
    '%taif%', '%qassim%', '%al ula%', '%neom%'
  ]));

DELETE FROM jobs
WHERE id IN (
    SELECT id FROM (
        SELECT id, row_number() OVER (
            PARTITION BY dedup_key ORDER BY collected_at DESC, id
        ) AS duplicate_number
        FROM jobs
    ) ranked
    WHERE duplicate_number > 1
);

ALTER TABLE jobs
    ALTER COLUMN dedup_key SET NOT NULL,
    ALTER COLUMN expires_at SET NOT NULL;

ALTER TABLE jobs
    ADD CONSTRAINT jobs_dedup_key_unique UNIQUE (dedup_key);

CREATE INDEX idx_jobs_expiry ON jobs(status, expires_at);

CREATE TABLE job_source_state (
    source VARCHAR(60) PRIMARY KEY,
    api_key_fingerprint CHAR(64),
    initial_backfill_completed BOOLEAN NOT NULL DEFAULT FALSE,
    requests_used INTEGER NOT NULL DEFAULT 0,
    request_budget INTEGER NOT NULL DEFAULT 500,
    budget_reset_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '36500 days'),
    last_request_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT job_source_state_requests_check CHECK (requests_used >= 0),
    CONSTRAINT job_source_state_budget_check CHECK (request_budget > 0)
);

INSERT INTO job_source_state(source) VALUES ('JOOBLE')
ON CONFLICT (source) DO NOTHING;
