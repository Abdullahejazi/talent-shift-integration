CREATE TABLE seed_candidates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_seed_id VARCHAR(80) NOT NULL,
    organization_name VARCHAR(200) NOT NULL,
    official_domain VARCHAR(255),
    source_url TEXT NOT NULL,
    source_type VARCHAR(60) NOT NULL,
    employer_search_query VARCHAR(240),
    country_code CHAR(2) NOT NULL DEFAULT 'SA',
    permission_status VARCHAR(40) NOT NULL DEFAULT 'NOT_YET_APPROVED',
    verification_status VARCHAR(80),
    terms_match_status VARCHAR(80),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    searched_at TIMESTAMPTZ,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_seed_candidates_country CHECK (country_code = 'SA')
);

CREATE UNIQUE INDEX uq_seed_candidates_identity
    ON seed_candidates(lower(organization_name), lower(coalesce(official_domain, '')));
CREATE INDEX idx_seed_candidates_unsearched ON seed_candidates(searched_at, imported_at);
