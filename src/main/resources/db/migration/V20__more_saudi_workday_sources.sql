WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('hp-workday','HP','https://hp.wd5.myworkdayjobs.com/en-US/externalcareersite','hp|externalcareersite'),
 ('solenis-workday','Solenis','https://solenis.wd1.myworkdayjobs.com/en-US/solenis','solenis|solenis'),
 ('workday-careers','Workday','https://workday.wd5.myworkdayjobs.com/en-US/Workday','workday|Workday'),
 ('four-seasons-workday','Four Seasons','https://fourseasons.wd3.myworkdayjobs.com/en-US/search','fourseasons|search'),
 ('mastercard-workday','Mastercard','https://mastercard.wd1.myworkdayjobs.com/en-US/Campus','mastercard|Campus'),
 ('atkinsrealis-workday','AtkinsRealis','https://slihrms.wd3.myworkdayjobs.com/en-US/Careers','slihrms|Careers'),
 ('fresenius-workday','Fresenius Medical Care','https://freseniusmedicalcare.wd3.myworkdayjobs.com/en-US/fme','freseniusmedicalcare|fme'),
 ('hpe-workday','Hewlett Packard Enterprise','https://hpe.wd5.myworkdayjobs.com/en-US/Jobsathpe','hpe|Jobsathpe'),
 ('dell-workday','Dell Technologies','https://dell.wd1.myworkdayjobs.com/en-US/External','dell|External'),
 ('jnj-workday','Johnson and Johnson','https://jj.wd5.myworkdayjobs.com/en-US/JJ','jj|JJ')
)
INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated,active)
SELECT slug,name,careers_url,careers_url,'PUBLIC_ATS_API',true,true FROM sources s
WHERE NOT EXISTS (SELECT 1 FROM companies c WHERE lower(c.name)=lower(s.name))
ON CONFLICT (slug) DO UPDATE SET careers_url=excluded.careers_url,source_type='PUBLIC_ATS_API',automated=true,active=true;

WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('hp-workday','HP','https://hp.wd5.myworkdayjobs.com/en-US/externalcareersite','hp|externalcareersite'),
 ('solenis-workday','Solenis','https://solenis.wd1.myworkdayjobs.com/en-US/solenis','solenis|solenis'),
 ('workday-careers','Workday','https://workday.wd5.myworkdayjobs.com/en-US/Workday','workday|Workday'),
 ('four-seasons-workday','Four Seasons','https://fourseasons.wd3.myworkdayjobs.com/en-US/search','fourseasons|search'),
 ('mastercard-workday','Mastercard','https://mastercard.wd1.myworkdayjobs.com/en-US/Campus','mastercard|Campus'),
 ('atkinsrealis-workday','AtkinsRealis','https://slihrms.wd3.myworkdayjobs.com/en-US/Careers','slihrms|Careers'),
 ('fresenius-workday','Fresenius Medical Care','https://freseniusmedicalcare.wd3.myworkdayjobs.com/en-US/fme','freseniusmedicalcare|fme'),
 ('hpe-workday','Hewlett Packard Enterprise','https://hpe.wd5.myworkdayjobs.com/en-US/Jobsathpe','hpe|Jobsathpe'),
 ('dell-workday','Dell Technologies','https://dell.wd1.myworkdayjobs.com/en-US/External','dell|External'),
 ('jnj-workday','Johnson and Johnson','https://jj.wd5.myworkdayjobs.com/en-US/JJ','jj|JJ')
)
INSERT INTO job_sources(company_id,company_name,careers_url,source_type,ats_provider,board_identifier,country,enabled,permission_status,refresh_interval_minutes,next_retry_at)
SELECT c.id,s.name,s.careers_url,'WORKDAY','WORKDAY',s.board_identifier,'SA',true,'PUBLIC_ALLOWED',10,now()
FROM sources s JOIN LATERAL (
 SELECT id FROM companies WHERE slug=s.slug OR lower(name)=lower(s.name)
 ORDER BY (slug=s.slug) DESC LIMIT 1
) c ON true
ON CONFLICT (lower(careers_url)) DO UPDATE SET source_type='WORKDAY',ats_provider='WORKDAY',board_identifier=excluded.board_identifier,
 enabled=true,permission_status='PUBLIC_ALLOWED',refresh_interval_minutes=10,next_retry_at=now(),updated_at=now();
