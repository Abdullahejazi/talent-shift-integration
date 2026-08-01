ALTER TABLE jobs
    ADD COLUMN country_code CHAR(2),
    ADD COLUMN saudi_relevant BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE job_collection_runs
    ADD COLUMN rejected_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_jobs_saudi_status_posted
    ON jobs(saudi_relevant, status, posted_at DESC);

CREATE TABLE companies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(120) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL UNIQUE,
    website_url TEXT NOT NULL,
    careers_url TEXT NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    automated BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT companies_source_type_check
        CHECK (source_type IN ('OFFICIAL_CAREER_SITE', 'PUBLIC_ATS_API', 'AGGREGATOR_API'))
);

INSERT INTO companies(slug, name, website_url, careers_url, source_type, automated) VALUES
    ('saudi-aramco', 'Saudi Aramco', 'https://www.aramco.com/', 'https://careers.aramco.com/en', 'OFFICIAL_CAREER_SITE', FALSE),
    ('sabic', 'SABIC', 'https://www.sabic.com/', 'https://jobs.sabic.com/lp/?locale=en_US', 'OFFICIAL_CAREER_SITE', FALSE),
    ('stc', 'stc', 'https://www.stc.com.sa/', 'https://careers.stc.com.sa/en?locale=en_US', 'OFFICIAL_CAREER_SITE', FALSE),
    ('neom', 'NEOM', 'https://www.neom.com/', 'https://careers.neom.com/careers/search', 'OFFICIAL_CAREER_SITE', FALSE),
    ('pif', 'Public Investment Fund', 'https://www.pif.gov.sa/', 'https://www.pif.gov.sa/en/careers/', 'OFFICIAL_CAREER_SITE', FALSE),
    ('maaden', 'Ma''aden', 'https://www.maaden.com/', 'https://www.maaden.com/join-our-team', 'OFFICIAL_CAREER_SITE', FALSE),
    ('riyadh-air', 'Riyadh Air', 'https://www.riyadhair.com/', 'https://www.riyadhair.com/en/careers', 'OFFICIAL_CAREER_SITE', FALSE),
    ('red-sea-global', 'Red Sea Global', 'https://www.redseaglobal.com/', 'https://www.redseaglobal.com/en/careers/', 'OFFICIAL_CAREER_SITE', FALSE),
    ('almarai', 'Almarai', 'https://www.almarai.com/', 'https://www.almarai.com/en/careers', 'OFFICIAL_CAREER_SITE', FALSE),
    ('saudia', 'Saudia Group', 'https://www.saudia.com/', 'https://careers.saudia.com/', 'OFFICIAL_CAREER_SITE', FALSE),
    ('al-rajhi-bank', 'Al Rajhi Bank', 'https://www.alrajhibank.com.sa/', 'https://careers.alrajhibank.com.sa/', 'OFFICIAL_CAREER_SITE', FALSE)
ON CONFLICT (slug) DO NOTHING;
