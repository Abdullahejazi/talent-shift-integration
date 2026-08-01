CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE connected_system (
 id uuid PRIMARY KEY, system_key varchar(80) NOT NULL UNIQUE, display_name varchar(160) NOT NULL,
 enabled boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sync_checkpoint (
 id uuid PRIMARY KEY, connected_system_id uuid NOT NULL REFERENCES connected_system(id), record_type varchar(20) NOT NULL,
 cursor_value varchar(500), last_success_at timestamptz, version bigint NOT NULL DEFAULT 0,
 UNIQUE(connected_system_id, record_type)
);
CREATE TABLE import_batch (
 id uuid PRIMARY KEY, connected_system_id uuid NOT NULL REFERENCES connected_system(id), status varchar(30) NOT NULL,
 source_cursor_start varchar(500), job_cursor_start varchar(500), source_cursor_end varchar(500), job_cursor_end varchar(500),
 imported_sources integer NOT NULL DEFAULT 0, imported_jobs integer NOT NULL DEFAULT 0,
 started_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz, error_message text
);
CREATE TABLE raw_source (
 id uuid PRIMARY KEY, import_batch_id uuid NOT NULL REFERENCES import_batch(id), connected_system_id uuid NOT NULL REFERENCES connected_system(id),
 external_record_id varchar(300) NOT NULL, payload jsonb NOT NULL, checksum char(64) NOT NULL, processing_status varchar(30) NOT NULL DEFAULT 'PENDING',
 received_at timestamptz NOT NULL DEFAULT now(), UNIQUE(connected_system_id, external_record_id, checksum)
);
CREATE INDEX idx_raw_source_pending ON raw_source(processing_status, received_at);
CREATE TABLE raw_job (
 id uuid PRIMARY KEY, import_batch_id uuid NOT NULL REFERENCES import_batch(id), connected_system_id uuid NOT NULL REFERENCES connected_system(id),
 external_record_id varchar(300) NOT NULL, payload jsonb NOT NULL, checksum char(64) NOT NULL, processing_status varchar(30) NOT NULL DEFAULT 'PENDING',
 received_at timestamptz NOT NULL DEFAULT now(), UNIQUE(connected_system_id, external_record_id, checksum)
);
CREATE INDEX idx_raw_job_pending ON raw_job(processing_status, received_at);
CREATE TABLE canonical_organization (
 id uuid PRIMARY KEY, normalized_name varchar(300) NOT NULL, display_name varchar(300) NOT NULL, official_domain varchar(253),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_org_name_domain ON canonical_organization(normalized_name, coalesce(official_domain,''));
CREATE TABLE canonical_source (
 id uuid PRIMARY KEY, canonical_organization_id uuid REFERENCES canonical_organization(id), display_name varchar(300),
 normalized_url varchar(2000), official_domain varchar(253), organization_name_normalized varchar(300),
 ats_provider varchar(100), ats_tenant_id varchar(300), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_source_url ON canonical_source(normalized_url) WHERE normalized_url IS NOT NULL;
CREATE UNIQUE INDEX uq_source_ats_tenant ON canonical_source(lower(ats_provider), ats_tenant_id) WHERE ats_provider IS NOT NULL AND ats_tenant_id IS NOT NULL;
CREATE TABLE canonical_job (
 id uuid PRIMARY KEY, canonical_source_id uuid NOT NULL REFERENCES canonical_source(id), title varchar(500) NOT NULL,
 organization_name varchar(300), location varchar(500), employment_type varchar(100), requisition_id varchar(300),
 normalized_job_url varchar(2000), normalized_apply_url varchar(2000), fingerprint char(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_job_url ON canonical_job(normalized_job_url) WHERE normalized_job_url IS NOT NULL;
CREATE UNIQUE INDEX uq_job_apply_url ON canonical_job(normalized_apply_url) WHERE normalized_apply_url IS NOT NULL;
CREATE INDEX idx_job_fingerprint ON canonical_job(fingerprint);
CREATE TABLE source_observation (
 id uuid PRIMARY KEY, canonical_source_id uuid NOT NULL REFERENCES canonical_source(id), raw_source_id uuid NOT NULL REFERENCES raw_source(id),
 connected_system_id uuid NOT NULL REFERENCES connected_system(id), external_record_id varchar(300) NOT NULL,
 duplicate_reason varchar(60) NOT NULL, confidence numeric(5,4) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(raw_source_id), UNIQUE(connected_system_id, external_record_id)
);
CREATE TABLE job_observation (
 id uuid PRIMARY KEY, canonical_job_id uuid NOT NULL REFERENCES canonical_job(id), raw_job_id uuid NOT NULL REFERENCES raw_job(id),
 connected_system_id uuid NOT NULL REFERENCES connected_system(id), external_record_id varchar(300) NOT NULL,
 duplicate_reason varchar(60) NOT NULL, confidence numeric(5,4) NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(raw_job_id), UNIQUE(connected_system_id, external_record_id)
);
CREATE UNIQUE INDEX uq_job_external_per_source ON job_observation(canonical_job_id, connected_system_id, external_record_id);
CREATE TABLE deduplication_candidate (
 id uuid PRIMARY KEY, entity_type varchar(20) NOT NULL, raw_record_id uuid NOT NULL, proposed_canonical_id uuid NOT NULL,
 reason varchar(60) NOT NULL, confidence numeric(5,4) NOT NULL, status varchar(20) NOT NULL DEFAULT 'PENDING',
 reviewed_by varchar(150), reviewed_at timestamptz, review_note text, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(entity_type, raw_record_id, proposed_canonical_id)
);
CREATE TABLE talent_shift_outbox (
 id uuid PRIMARY KEY, aggregate_type varchar(30) NOT NULL, aggregate_id uuid NOT NULL, payload jsonb NOT NULL,
 idempotency_key varchar(200) NOT NULL UNIQUE, status varchar(30) NOT NULL DEFAULT 'PENDING', attempt_count integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL DEFAULT now(), last_error text, created_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz
);
CREATE INDEX idx_outbox_ready ON talent_shift_outbox(status, next_attempt_at);
CREATE TABLE transfer_attempt (
 id uuid PRIMARY KEY, outbox_id uuid NOT NULL REFERENCES talent_shift_outbox(id), attempt_number integer NOT NULL,
 idempotency_key varchar(200) NOT NULL, status varchar(30) NOT NULL, response_code integer, response_body text,
 started_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz, UNIQUE(outbox_id, attempt_number)
);
