CREATE TABLE job_runtime_locks (
    lock_name VARCHAR(80) PRIMARY KEY,
    owner_id UUID,
    lease_until TIMESTAMPTZ NOT NULL DEFAULT '-infinity'
);

INSERT INTO job_runtime_locks(lock_name) VALUES ('job-collection'), ('auto-discovery')
ON CONFLICT DO NOTHING;

ALTER TABLE jobs ADD COLUMN IF NOT EXISTS status_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ;

CREATE TABLE job_status_events (
    id BIGSERIAL PRIMARY KEY,
    job_id UUID NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_job_status_events_time ON job_status_events(changed_at, new_status);
CREATE INDEX idx_job_status_events_job ON job_status_events(job_id, changed_at DESC);

CREATE OR REPLACE FUNCTION record_job_status_change() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status THEN
        NEW.status_changed_at := now();
        NEW.closed_at := CASE WHEN NEW.status IN ('EXPIRED','INACTIVE','OUT_OF_SCOPE') THEN now() ELSE NULL END;
        INSERT INTO job_status_events(job_id,previous_status,new_status,changed_at)
        VALUES (NEW.id,CASE WHEN TG_OP='INSERT' THEN NULL ELSE OLD.status END,NEW.status,now());
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_job_status_change ON jobs;
CREATE TRIGGER trg_job_status_change BEFORE INSERT OR UPDATE OF status ON jobs
FOR EACH ROW EXECUTE FUNCTION record_job_status_change();

ALTER TABLE job_sources ADD COLUMN IF NOT EXISTS productive_runs INTEGER NOT NULL DEFAULT 0;
ALTER TABLE job_sources ADD COLUMN IF NOT EXISTS empty_runs INTEGER NOT NULL DEFAULT 0;

CREATE TABLE backend_discovery_queries (
    query_hash CHAR(64) PRIMARY KEY,
    query_text TEXT NOT NULL,
    search_kind VARCHAR(30) NOT NULL,
    last_searched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    run_count INTEGER NOT NULL DEFAULT 1,
    result_count INTEGER NOT NULL DEFAULT 0,
    jobs_accepted INTEGER NOT NULL DEFAULT 0,
    seeds_promoted INTEGER NOT NULL DEFAULT 0
);

CREATE OR REPLACE VIEW daily_job_operations AS
SELECT day,
       COALESCE(inserted,0) AS inserted,
       COALESCE(activated,0) AS activated,
       COALESCE(expired,0) AS expired,
       COALESCE(inactivated,0) AS inactivated,
       COALESCE(out_of_scope,0) AS out_of_scope,
       COALESCE(inserted,0)-COALESCE(expired,0)-COALESCE(inactivated,0)-COALESCE(out_of_scope,0) AS net_change
FROM (
    SELECT generate_series(current_date-30,current_date,interval '1 day')::date AS day
) calendar
LEFT JOIN (
    SELECT first_seen_at::date AS day,count(*) AS inserted FROM jobs GROUP BY 1
) additions USING(day)
LEFT JOIN (
    SELECT changed_at::date AS day,
      count(*) FILTER(WHERE new_status='ACTIVE' AND previous_status IS NOT NULL) AS activated,
      count(*) FILTER(WHERE new_status='EXPIRED') AS expired,
      count(*) FILTER(WHERE new_status='INACTIVE') AS inactivated,
      count(*) FILTER(WHERE new_status='OUT_OF_SCOPE') AS out_of_scope
    FROM job_status_events GROUP BY 1
) transitions USING(day);
