WITH sources(slug,name,careers_url) AS (VALUES
 ('saudi-gold-refinery-careers','Saudi Gold Refinery','https://sgr.careers-page.com/'),
 ('nakhla-ai-careers','Nakhla AI','https://nakhlaai.careers-page.com/')
)
INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated,active)
SELECT slug,name,careers_url,careers_url,'OFFICIAL_CAREER_SITE',true,true FROM sources s
WHERE NOT EXISTS (SELECT 1 FROM companies c WHERE lower(c.name)=lower(s.name))
ON CONFLICT (slug) DO UPDATE SET careers_url=excluded.careers_url,source_type='OFFICIAL_CAREER_SITE',automated=true,active=true;

WITH sources(slug,name,careers_url) AS (VALUES
 ('saudi-gold-refinery-careers','Saudi Gold Refinery','https://sgr.careers-page.com/'),
 ('nakhla-ai-careers','Nakhla AI','https://nakhlaai.careers-page.com/')
)
INSERT INTO job_sources(company_id,company_name,careers_url,source_type,country,enabled,permission_status,refresh_interval_minutes,next_retry_at)
SELECT c.id,s.name,s.careers_url,'GENERIC_HTML','SA',true,'PUBLIC_ALLOWED',10,now()
FROM sources s JOIN LATERAL (
 SELECT id FROM companies WHERE slug=s.slug OR lower(name)=lower(s.name)
 ORDER BY (slug=s.slug) DESC LIMIT 1
) c ON true
ON CONFLICT (lower(careers_url)) DO UPDATE SET source_type='GENERIC_HTML',enabled=true,permission_status='PUBLIC_ALLOWED',
 refresh_interval_minutes=10,next_retry_at=now(),updated_at=now();
