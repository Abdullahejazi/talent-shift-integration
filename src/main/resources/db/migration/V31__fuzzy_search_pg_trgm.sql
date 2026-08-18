-- Enable the trigram extension for fuzzy string matching
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Create GIN indexes for rapid fuzzy searches and similarity matching
CREATE INDEX IF NOT EXISTS idx_jobs_title_trgm ON jobs USING gin (lower(title) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_jobs_company_trgm ON jobs USING gin (lower(company) gin_trgm_ops);
