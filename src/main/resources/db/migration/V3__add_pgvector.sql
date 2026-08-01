-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Add the vector column (3 dimensions for our basic concepts: Tech, Culinary, Management)
ALTER TABLE canonical_job ADD COLUMN IF NOT EXISTS title_vector vector(3);

-- Create an HNSW index on the vector column for fast nearest-neighbor search
CREATE INDEX IF NOT EXISTS canonical_job_title_vector_idx ON canonical_job USING hnsw (title_vector vector_l2_ops);
