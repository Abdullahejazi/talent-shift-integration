UPDATE job_sources
SET enabled=true,
    consecutive_failures=0,
    last_error_code=NULL,
    circuit_open_until=NULL,
    next_retry_at=now(),
    updated_at=now()
WHERE permission_status IN ('PUBLIC_ALLOWED','API_LICENSED')
  AND last_error_code IN ('REDIRECT_WITHOUT_LOCATION','RESPONSE_TOO_LARGE');
