WITH ranked AS (
    SELECT id, row_number() OVER (PARTITION BY dedup_key ORDER BY collected_at DESC, id) AS position
    FROM jobs WHERE status='ACTIVE'
)
UPDATE jobs SET status='INACTIVE'
WHERE id IN (SELECT id FROM ranked WHERE position > 1);

CREATE UNIQUE INDEX IF NOT EXISTS uq_jobs_active_dedup_key
    ON jobs(dedup_key) WHERE status='ACTIVE';

CREATE UNIQUE INDEX IF NOT EXISTS uq_companies_name_case_insensitive
    ON companies(lower(name)) WHERE active=true;

CREATE UNIQUE INDEX IF NOT EXISTS uq_companies_careers_url_case_insensitive
    ON companies(lower(careers_url)) WHERE active=true;
