CREATE TABLE source_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

ALTER TABLE job_sources 
ADD COLUMN category_id UUID REFERENCES source_categories(id) ON DELETE SET NULL;

CREATE INDEX idx_job_sources_category ON job_sources(category_id);
