TRUNCATE TABLE job_sources CASCADE;

INSERT INTO job_sources (id, company_name, careers_url, source_type, ats_provider, board_identifier, enabled, country) VALUES 
(gen_random_uuid(), 'Stripe', 'https://job-boards.greenhouse.io/stripe', 'GREENHOUSE', 'GREENHOUSE', 'stripe', true, 'SA'),
(gen_random_uuid(), 'Discord', 'https://job-boards.greenhouse.io/discord', 'GREENHOUSE', 'GREENHOUSE', 'discord', true, 'SA'),
(gen_random_uuid(), 'Vercel', 'https://job-boards.greenhouse.io/vercel', 'GREENHOUSE', 'GREENHOUSE', 'vercel', true, 'SA'),
(gen_random_uuid(), 'Plaid', 'https://jobs.ashbyhq.com/plaid', 'ASHBY', 'ASHBY', 'plaid', true, 'SA'),
(gen_random_uuid(), 'Mozilla', 'https://job-boards.greenhouse.io/mozilla', 'GREENHOUSE', 'GREENHOUSE', 'mozilla', true, 'SA'),
(gen_random_uuid(), 'Coinbase', 'https://job-boards.greenhouse.io/coinbase', 'GREENHOUSE', 'GREENHOUSE', 'coinbase', true, 'SA'),
(gen_random_uuid(), 'Airbnb', 'https://job-boards.greenhouse.io/airbnb', 'GREENHOUSE', 'GREENHOUSE', 'airbnb', true, 'SA'),
(gen_random_uuid(), 'Dropbox', 'https://job-boards.greenhouse.io/dropbox', 'GREENHOUSE', 'GREENHOUSE', 'dropbox', true, 'SA'),
(gen_random_uuid(), 'Google', 'https://www.google.com/about/careers/applications/', 'GENERIC_HTML', null, null, true, 'SA'),
(gen_random_uuid(), 'Twilio', 'https://job-boards.greenhouse.io/twilio', 'GREENHOUSE', 'GREENHOUSE', 'twilio', true, 'SA');
