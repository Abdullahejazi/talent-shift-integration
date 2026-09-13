-- Register Saudi tech companies with their ATS platforms
-- so SeedCompanyJobSource can automatically collect their jobs

INSERT INTO companies(slug, name, website_url, careers_url, source_type, automated) VALUES
    -- Workable-powered companies
    ('salla',    'Salla',    'https://salla.com/',    'https://apply.workable.com/salla',    'PUBLIC_ATS_API', TRUE),
    ('foodics',  'Foodics',  'https://foodics.com/',  'https://apply.workable.com/foodics',  'PUBLIC_ATS_API', TRUE),
    ('jahez',    'Jahez',    'https://jahez.com/',    'https://apply.workable.com/jahez',    'PUBLIC_ATS_API', TRUE),

    -- Recruitee-powered companies
    ('zid',      'Zid',      'https://zid.sa/',       'https://zid.recruitee.com',           'PUBLIC_ATS_API', TRUE)

ON CONFLICT (slug) DO UPDATE SET
    careers_url = EXCLUDED.careers_url,
    automated   = TRUE,
    active      = TRUE;
