WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('salesforce-workday','Salesforce','https://salesforce.wd12.myworkdayjobs.com/en-US/External_Career_Site','salesforce|External_Career_Site'),
 ('parsons-workday','Parsons','https://parsons.wd5.myworkdayjobs.com/en-US/Search','parsons|Search'),
 ('jll-workday','JLL','https://jll.wd1.myworkdayjobs.com/en-US/jllcareers','jll|jllcareers'),
 ('adobe-workday','Adobe','https://adobe.wd5.myworkdayjobs.com/en-US/external_experienced','adobe|external_experienced'),
 ('motorola-solutions-workday','Motorola Solutions','https://motorolasolutions.wd5.myworkdayjobs.com/en-US/Careers','motorolasolutions|Careers'),
 ('3m-workday','3M','https://3m.wd1.myworkdayjobs.com/en-US/Search','3m|Search'),
 ('mastercard-corporate-workday','Mastercard Corporate','https://mastercard.wd1.myworkdayjobs.com/en-US/CorporateCareers','mastercard|CorporateCareers')
)
INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated,active)
SELECT slug,name,careers_url,careers_url,'PUBLIC_ATS_API',true,true FROM sources s
WHERE NOT EXISTS (SELECT 1 FROM companies c WHERE lower(c.name)=lower(s.name))
ON CONFLICT (slug) DO UPDATE SET careers_url=excluded.careers_url,source_type='PUBLIC_ATS_API',automated=true,active=true;

WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('salesforce-workday','Salesforce','https://salesforce.wd12.myworkdayjobs.com/en-US/External_Career_Site','salesforce|External_Career_Site'),
 ('parsons-workday','Parsons','https://parsons.wd5.myworkdayjobs.com/en-US/Search','parsons|Search'),
 ('jll-workday','JLL','https://jll.wd1.myworkdayjobs.com/en-US/jllcareers','jll|jllcareers'),
 ('adobe-workday','Adobe','https://adobe.wd5.myworkdayjobs.com/en-US/external_experienced','adobe|external_experienced'),
 ('motorola-solutions-workday','Motorola Solutions','https://motorolasolutions.wd5.myworkdayjobs.com/en-US/Careers','motorolasolutions|Careers'),
 ('3m-workday','3M','https://3m.wd1.myworkdayjobs.com/en-US/Search','3m|Search'),
 ('mastercard-corporate-workday','Mastercard Corporate','https://mastercard.wd1.myworkdayjobs.com/en-US/CorporateCareers','mastercard|CorporateCareers')
)
INSERT INTO job_sources(company_id,company_name,careers_url,source_type,ats_provider,board_identifier,
 country,enabled,permission_status,refresh_interval_minutes,next_retry_at)
SELECT c.id,s.name,s.careers_url,'WORKDAY','WORKDAY',s.board_identifier,'SA',true,'PUBLIC_ALLOWED',10,now()
FROM sources s JOIN LATERAL (
 SELECT id FROM companies WHERE slug=s.slug OR lower(name)=lower(s.name)
 ORDER BY (slug=s.slug) DESC LIMIT 1
) c ON true
ON CONFLICT (lower(careers_url)) DO UPDATE SET source_type='WORKDAY',ats_provider='WORKDAY',
 board_identifier=excluded.board_identifier,enabled=true,permission_status='PUBLIC_ALLOWED',
 refresh_interval_minutes=10,next_retry_at=now(),updated_at=now();
