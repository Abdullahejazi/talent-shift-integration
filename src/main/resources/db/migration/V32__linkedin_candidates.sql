CREATE TABLE linkedin_candidates (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    linkedin_url      TEXT        NOT NULL,
    linkedin_handle   VARCHAR(120) NOT NULL,
    full_name         VARCHAR(200) NOT NULL,
    headline          VARCHAR(300),
    summary           TEXT,
    title             VARCHAR(240),
    company           VARCHAR(200),
    location          VARCHAR(200),
    country_code      CHAR(2)     NOT NULL DEFAULT 'SA',
    discipline        VARCHAR(100),
    experience_years  INT,
    skills            TEXT[],
    email             VARCHAR(200),
    phone             VARCHAR(60),
    profile_photo_url TEXT,
    is_open_to_work   BOOLEAN     NOT NULL DEFAULT FALSE,
    collected_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_verified_at  TIMESTAMPTZ,
    is_active         BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE UNIQUE INDEX uq_linkedin_candidates_url
    ON linkedin_candidates(linkedin_url);

CREATE INDEX idx_linkedin_candidates_active
    ON linkedin_candidates(country_code, is_active, collected_at DESC);

CREATE INDEX idx_linkedin_candidates_discipline
    ON linkedin_candidates(discipline);
