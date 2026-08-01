-- HTTP 403 responses from public ATS endpoints can be transient anti-automation
-- or rate-limit responses. They must use circuit/backoff handling instead of
-- permanently removing previously healthy sources from collection.
UPDATE job_sources
SET enabled = true,
    consecutive_failures = 0,
    next_retry_at = now(),
    circuit_open_until = NULL,
    updated_at = now()
WHERE source_type = 'GREENHOUSE'
  AND enabled = false
  AND last_error_code = 'HTTP_403'
  AND last_success_at IS NOT NULL;
