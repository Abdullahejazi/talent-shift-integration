CREATE TABLE connected_systems (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    system_key VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    system_type VARCHAR(60) NOT NULL DEFAULT 'PUBLIC_WEB',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE import_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connected_system_id UUID REFERENCES connected_systems(id) ON DELETE SET NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
    records_received INTEGER NOT NULL DEFAULT 0,
    records_accepted INTEGER NOT NULL DEFAULT 0,
    records_rejected INTEGER NOT NULL DEFAULT 0,
    cursor_value TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    error_message TEXT
);

CREATE TABLE raw_job_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_batch_id UUID REFERENCES import_batches(id) ON DELETE SET NULL,
    connected_system_id UUID REFERENCES connected_systems(id) ON DELETE SET NULL,
    source_id UUID REFERENCES job_sources(id) ON DELETE SET NULL,
    external_record_id VARCHAR(300),
    payload JSONB NOT NULL,
    checksum CHAR(64) NOT NULL,
    processing_status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    rejection_reason TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_raw_job_records_received ON raw_job_records(received_at DESC);
CREATE INDEX idx_raw_job_records_checksum ON raw_job_records(checksum);

CREATE TABLE job_lineage_observations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    raw_record_id UUID REFERENCES raw_job_records(id) ON DELETE SET NULL,
    source_id UUID REFERENCES job_sources(id) ON DELETE SET NULL,
    observation_type VARCHAR(40) NOT NULL DEFAULT 'COLLECTED',
    observed_url TEXT,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    details JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX idx_job_lineage_job ON job_lineage_observations(job_id, observed_at DESC);

CREATE TABLE deduplication_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_job_id UUID REFERENCES jobs(id) ON DELETE CASCADE,
    existing_job_id UUID REFERENCES jobs(id) ON DELETE CASCADE,
    confidence NUMERIC(5,4),
    reason TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decision_note TEXT,
    decided_by VARCHAR(320),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ
);
CREATE INDEX idx_dedup_reviews_status ON deduplication_reviews(status, created_at DESC);

CREATE TABLE sync_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connected_system_id UUID NOT NULL REFERENCES connected_systems(id) ON DELETE CASCADE,
    record_type VARCHAR(50) NOT NULL,
    cursor_value TEXT,
    last_success_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (connected_system_id, record_type)
);

CREATE TABLE integration_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(60) NOT NULL,
    aggregate_id UUID NOT NULL,
    destination_key VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ
);
CREATE INDEX idx_integration_outbox_ready ON integration_outbox(status, next_attempt_at);

CREATE TABLE integration_transfer_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    outbox_id UUID NOT NULL REFERENCES integration_outbox(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_code INTEGER,
    response_body TEXT,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (outbox_id, attempt_number)
);

CREATE TABLE integration_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor VARCHAR(320) NOT NULL,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id VARCHAR(100),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_integration_audit_created ON integration_audit_log(created_at DESC);

CREATE TABLE job_merge_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    winner_job_id UUID REFERENCES jobs(id) ON DELETE SET NULL,
    loser_job_id UUID,
    reason TEXT NOT NULL,
    snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'APPLIED',
    merged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reversed_at TIMESTAMPTZ
);

INSERT INTO connected_systems(system_key, display_name, system_type, configuration)
VALUES ('talentshift-public-collection', 'TalentShift public collection pipeline', 'INTERNAL',
        '{"credentialFree":true,"collectorMode":"fast"}'::jsonb)
ON CONFLICT (system_key) DO NOTHING;
