CREATE TEMP TABLE verified_remote_sources (
    slug TEXT, company_name TEXT, careers_url TEXT, source_type TEXT, provider TEXT, board_identifier TEXT
) ON COMMIT DROP;

INSERT INTO verified_remote_sources VALUES
('global-ats-clickhouse','ClickHouse','https://jobs.ashbyhq.com/clickhouse','ASHBY','ASHBY','clickhouse'),
('global-ats-cursor','Cursor','https://jobs.ashbyhq.com/cursor','ASHBY','ASHBY','cursor'),
('global-ats-miro','Miro','https://jobs.ashbyhq.com/miro','ASHBY','ASHBY','miro'),
('global-ats-openai','OpenAI','https://jobs.ashbyhq.com/openai','ASHBY','ASHBY','openai'),
('global-ats-oyster','Oyster','https://jobs.ashbyhq.com/oyster','ASHBY','ASHBY','oyster'),
('global-ats-plaid','Plaid','https://jobs.ashbyhq.com/plaid','ASHBY','ASHBY','plaid'),
('global-ats-posthog','PostHog','https://jobs.ashbyhq.com/posthog','ASHBY','ASHBY','posthog'),
('global-ats-railway','Railway','https://jobs.ashbyhq.com/railway','ASHBY','ASHBY','railway'),
('global-ats-ramp','Ramp','https://jobs.ashbyhq.com/ramp','ASHBY','ASHBY','ramp'),
('global-ats-render','Render','https://jobs.ashbyhq.com/render','ASHBY','ASHBY','render'),
('global-ats-replit','Replit','https://jobs.ashbyhq.com/replit','ASHBY','ASHBY','replit'),
('global-ats-supabase','Supabase','https://jobs.ashbyhq.com/supabase','ASHBY','ASHBY','supabase'),
('global-ats-zapier','Zapier','https://jobs.ashbyhq.com/zapier','ASHBY','ASHBY','zapier'),
('global-ats-affirm','Affirm','https://job-boards.greenhouse.io/affirm','GREENHOUSE','GREENHOUSE','affirm'),
('global-ats-airbnb','Airbnb','https://job-boards.greenhouse.io/airbnb','GREENHOUSE','GREENHOUSE','airbnb'),
('global-ats-asana','Asana','https://job-boards.greenhouse.io/asana','GREENHOUSE','GREENHOUSE','asana'),
('global-ats-bitwarden','Bitwarden','https://job-boards.greenhouse.io/bitwarden','GREENHOUSE','GREENHOUSE','bitwarden'),
('global-ats-braze','Braze','https://job-boards.greenhouse.io/braze','GREENHOUSE','GREENHOUSE','braze'),
('global-ats-calendly','Calendly','https://job-boards.greenhouse.io/calendly','GREENHOUSE','GREENHOUSE','calendly'),
('global-ats-chime','Chime','https://job-boards.greenhouse.io/chime','GREENHOUSE','GREENHOUSE','chime'),
('global-ats-circleci','CircleCI','https://job-boards.greenhouse.io/circleci','GREENHOUSE','GREENHOUSE','circleci'),
('global-ats-cockroach-labs','Cockroach Labs','https://job-boards.greenhouse.io/cockroachlabs','GREENHOUSE','GREENHOUSE','cockroachlabs'),
('global-ats-coinbase','Coinbase','https://job-boards.greenhouse.io/coinbase','GREENHOUSE','GREENHOUSE','coinbase'),
('global-ats-consensys','Consensys','https://job-boards.greenhouse.io/consensys','GREENHOUSE','GREENHOUSE','consensys'),
('global-ats-datadog','Datadog','https://job-boards.greenhouse.io/datadog','GREENHOUSE','GREENHOUSE','datadog'),
('global-ats-discord','Discord','https://job-boards.greenhouse.io/discord','GREENHOUSE','GREENHOUSE','discord'),
('global-ats-dropbox','Dropbox','https://job-boards.greenhouse.io/dropbox','GREENHOUSE','GREENHOUSE','dropbox'),
('global-ats-fastly','Fastly','https://job-boards.greenhouse.io/fastly','GREENHOUSE','GREENHOUSE','fastly'),
('global-ats-gitlab','GitLab','https://job-boards.greenhouse.io/gitlab','GREENHOUSE','GREENHOUSE','gitlab'),
('global-ats-grafana-labs','Grafana Labs','https://job-boards.greenhouse.io/grafanalabs','GREENHOUSE','GREENHOUSE','grafanalabs'),
('global-ats-gusto','Gusto','https://job-boards.greenhouse.io/gusto','GREENHOUSE','GREENHOUSE','gusto'),
('global-ats-instacart','Instacart','https://job-boards.greenhouse.io/instacart','GREENHOUSE','GREENHOUSE','instacart'),
('global-ats-intercom','Intercom','https://job-boards.greenhouse.io/intercom','GREENHOUSE','GREENHOUSE','intercom'),
('global-ats-launchdarkly','LaunchDarkly','https://job-boards.greenhouse.io/launchdarkly','GREENHOUSE','GREENHOUSE','launchdarkly'),
('global-ats-lyft','Lyft','https://job-boards.greenhouse.io/lyft','GREENHOUSE','GREENHOUSE','lyft'),
('global-ats-mercury','Mercury','https://job-boards.greenhouse.io/mercury','GREENHOUSE','GREENHOUSE','mercury'),
('global-ats-mozilla','Mozilla','https://job-boards.greenhouse.io/mozilla','GREENHOUSE','GREENHOUSE','mozilla'),
('global-ats-netlify','Netlify','https://job-boards.greenhouse.io/netlify','GREENHOUSE','GREENHOUSE','netlify'),
('global-ats-pagerduty','PagerDuty','https://job-boards.greenhouse.io/pagerduty','GREENHOUSE','GREENHOUSE','pagerduty'),
('global-ats-postman','Postman','https://job-boards.greenhouse.io/postman','GREENHOUSE','GREENHOUSE','postman'),
('global-ats-proton','Proton','https://job-boards.greenhouse.io/proton','GREENHOUSE','GREENHOUSE','proton'),
('global-ats-reddit','Reddit','https://job-boards.greenhouse.io/reddit','GREENHOUSE','GREENHOUSE','reddit'),
('global-ats-remote','Remote','https://job-boards.greenhouse.io/remotecom','GREENHOUSE','GREENHOUSE','remotecom'),
('global-ats-samsara','Samsara','https://job-boards.greenhouse.io/samsara','GREENHOUSE','GREENHOUSE','samsara'),
('global-ats-stripe','Stripe','https://job-boards.greenhouse.io/stripe','GREENHOUSE','GREENHOUSE','stripe'),
('global-ats-twilio','Twilio','https://job-boards.greenhouse.io/twilio','GREENHOUSE','GREENHOUSE','twilio'),
('global-ats-typeform','Typeform','https://job-boards.greenhouse.io/typeform','GREENHOUSE','GREENHOUSE','typeform'),
('global-ats-udemy','Udemy','https://job-boards.greenhouse.io/udemy','GREENHOUSE','GREENHOUSE','udemy'),
('global-ats-vercel','Vercel','https://job-boards.greenhouse.io/vercel','GREENHOUSE','GREENHOUSE','vercel'),
('global-ats-webflow','Webflow','https://job-boards.greenhouse.io/webflow','GREENHOUSE','GREENHOUSE','webflow'),
('global-ats-wikimedia','Wikimedia Foundation','https://job-boards.greenhouse.io/wikimedia','GREENHOUSE','GREENHOUSE','wikimedia');

INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated,active)
SELECT slug,company_name,careers_url,careers_url,'PUBLIC_ATS_API',true,true
FROM verified_remote_sources
ON CONFLICT DO NOTHING;

INSERT INTO job_sources(company_id,company_name,careers_url,source_type,ats_provider,board_identifier,
                        country,enabled,permission_status,refresh_interval_minutes)
SELECT c.id,v.company_name,v.careers_url,v.source_type,v.provider,v.board_identifier,
       'SA',true,'PUBLIC_ALLOWED',30
FROM verified_remote_sources v
JOIN companies c ON c.slug=v.slug
ON CONFLICT (lower(careers_url)) DO UPDATE SET
    enabled=true,permission_status='PUBLIC_ALLOWED',source_type=EXCLUDED.source_type,
    ats_provider=EXCLUDED.ats_provider,board_identifier=EXCLUDED.board_identifier,
    next_retry_at=now(),updated_at=now();
