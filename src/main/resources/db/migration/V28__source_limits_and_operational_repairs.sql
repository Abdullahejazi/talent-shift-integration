-- NULL means unlimited. The requested production default is 10,000 sources.
CREATE TABLE system_settings (
    setting_key VARCHAR(80) PRIMARY KEY,
    integer_value BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO system_settings(setting_key,integer_value) VALUES ('maximum_job_sources',10000)
ON CONFLICT(setting_key) DO NOTHING;

CREATE OR REPLACE FUNCTION enforce_job_source_limit() RETURNS trigger AS $$
DECLARE maximum BIGINT;
BEGIN
    IF EXISTS(SELECT 1 FROM job_sources WHERE lower(careers_url)=lower(NEW.careers_url)) THEN
        RETURN NEW;
    END IF;
    SELECT integer_value INTO maximum FROM system_settings WHERE setting_key='maximum_job_sources';
    IF maximum IS NOT NULL THEN
        LOCK TABLE job_sources IN SHARE ROW EXCLUSIVE MODE;
        IF (SELECT count(*) FROM job_sources) >= maximum THEN
            RAISE EXCEPTION 'Job source limit of % reached', maximum USING ERRCODE='check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_job_source_limit ON job_sources;
CREATE TRIGGER trg_job_source_limit BEFORE INSERT ON job_sources
FOR EACH ROW EXECUTE FUNCTION enforce_job_source_limit();

-- Historical insertion counts must survive later deduplication/deletion.
CREATE OR REPLACE VIEW daily_job_operations AS
SELECT day,
       COALESCE(inserted,0) AS inserted,
       COALESCE(activated,0) AS activated,
       COALESCE(expired,0) AS expired,
       COALESCE(inactivated,0) AS inactivated,
       COALESCE(out_of_scope,0) AS out_of_scope,
       COALESCE(inserted,0)-COALESCE(expired,0)-COALESCE(inactivated,0)-COALESCE(out_of_scope,0) AS net_change
FROM (SELECT generate_series(current_date-30,current_date,interval '1 day')::date AS day) calendar
LEFT JOIN (
    SELECT changed_at::date AS day,
      count(*) FILTER(WHERE previous_status IS NULL) AS inserted,
      count(*) FILTER(WHERE new_status='ACTIVE' AND previous_status IS NOT NULL) AS activated,
      count(*) FILTER(WHERE new_status='EXPIRED') AS expired,
      count(*) FILTER(WHERE new_status='INACTIVE') AS inactivated,
      count(*) FILTER(WHERE new_status='OUT_OF_SCOPE') AS out_of_scope
    FROM job_status_events GROUP BY 1
) events USING(day);

UPDATE job_collection_runs SET status='ABANDONED',finished_at=now(),
  failed_sources=concat_ws(',',nullif(failed_sources,''),'PROCESS_TERMINATED')
WHERE status='RUNNING' AND started_at<now()-interval '2 hours';
