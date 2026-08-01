INSERT INTO companies(slug, name, website_url, careers_url, source_type, automated) VALUES
('trendyol', 'Trendyol', 'https://www.trendyol.com/', 'https://jobs.lever.co/trendyol', 'PUBLIC_ATS_API', TRUE),
('soum', 'SOUM', 'https://soum.sa/', 'https://jobs.lever.co/soum', 'PUBLIC_ATS_API', TRUE),
('flowlife', 'Flow', 'https://www.flow.life/', 'https://jobs.lever.co/flowlife', 'PUBLIC_ATS_API', TRUE),
('lalamove', 'Lalamove', 'https://www.lalamove.com/', 'https://jobs.lever.co/lalamove', 'PUBLIC_ATS_API', TRUE),
('dlocal', 'dLocal', 'https://www.dlocal.com/', 'https://jobs.lever.co/dlocal', 'PUBLIC_ATS_API', TRUE),
('weloglobal', 'Welo Global', 'https://welocalize.com/', 'https://jobs.lever.co/weloglobal', 'PUBLIC_ATS_API', TRUE),
('infinite-pl', 'Infinite pl', 'https://infinitepl.com/', 'https://jobs.lever.co/infinitepl', 'PUBLIC_ATS_API', TRUE),
('ajax-systems', 'Ajax Systems', 'https://ajax.systems/', 'https://jobs.lever.co/ajax', 'PUBLIC_ATS_API', TRUE),
('jobgether', 'Jobgether', 'https://jobgether.com/', 'https://jobs.lever.co/jobgether', 'PUBLIC_ATS_API', TRUE),
('tamara', 'Tamara', 'https://tamara.co/', 'https://job-boards.greenhouse.io/tamara', 'PUBLIC_ATS_API', TRUE),
('hala', 'HALA', 'https://hala.com/', 'https://job-boards.greenhouse.io/hala', 'PUBLIC_ATS_API', TRUE),
('lucid-motors', 'Lucid Motors', 'https://lucidmotors.com/', 'https://job-boards.greenhouse.io/lucidmotors', 'PUBLIC_ATS_API', TRUE),
('jensen-hughes', 'Jensen Hughes', 'https://www.jensenhughes.com/', 'https://job-boards.greenhouse.io/jensenhughes', 'PUBLIC_ATS_API', TRUE)
ON CONFLICT (slug) DO UPDATE SET careers_url=EXCLUDED.careers_url, source_type=EXCLUDED.source_type, automated=TRUE, active=TRUE;
