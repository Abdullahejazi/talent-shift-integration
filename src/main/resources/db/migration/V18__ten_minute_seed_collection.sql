ALTER TABLE job_sources
    ALTER COLUMN refresh_interval_minutes SET DEFAULT 10;

UPDATE job_sources
SET refresh_interval_minutes = 10,
    next_retry_at = LEAST(next_retry_at, now() + interval '10 minutes'),
    updated_at = now()
WHERE enabled = true
  AND permission_status IN ('PUBLIC_ALLOWED', 'API_LICENSED');
