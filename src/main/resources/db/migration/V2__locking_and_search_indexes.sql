CREATE INDEX idx_source_domain ON canonical_source(lower(official_domain));
CREATE INDEX idx_source_org_name_trgm ON canonical_source USING gin (organization_name_normalized gin_trgm_ops);
CREATE INDEX idx_job_title_trgm ON canonical_job USING gin (lower(title) gin_trgm_ops);
CREATE INDEX idx_candidate_pending ON deduplication_candidate(status, entity_type, created_at);
COMMENT ON TABLE raw_source IS 'Immutable source payloads exactly as received; duplicate versions are retained by checksum.';
COMMENT ON TABLE raw_job IS 'Immutable job payloads exactly as received; duplicate versions are retained by checksum.';
