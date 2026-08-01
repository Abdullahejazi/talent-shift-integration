WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('comcast-workday','Comcast','https://comcast.wd5.myworkdayjobs.com/en-US/Comcast_Careers','comcast|Comcast_Careers'),
 ('dynata-workday','Dynata','https://dynata.wd108.myworkdayjobs.com/en-US/careers','dynata|careers'),
 ('vanguard-workday','Vanguard','https://vanguard.wd5.myworkdayjobs.com/en-US/vanguard_external','vanguard|vanguard_external'),
 ('guardian-life-workday','Guardian Life','https://guardianlife.wd5.myworkdayjobs.com/en-US/Guardian-Life-Careers','guardianlife|Guardian-Life-Careers'),
 ('accenture-workday','Accenture','https://accenture.wd103.myworkdayjobs.com/en-US/AccentureCareers','accenture|AccentureCareers'),
 ('acquire-workday','Acquire','https://acquireai.wd102.myworkdayjobs.com/en-US/Acquire','acquireai|Acquire'),
 ('booz-allen-workday','Booz Allen Hamilton','https://bah.wd1.myworkdayjobs.com/en-US/BAH_Jobs','bah|BAH_Jobs'),
 ('corebridge-workday','Corebridge Financial','https://corebridgefinancial.wd1.myworkdayjobs.com/en-US/CorebridgeFinancial','corebridgefinancial|CorebridgeFinancial'),
 ('general-motors-workday','General Motors','https://generalmotors.wd5.myworkdayjobs.com/en-US/Careers_GM','generalmotors|Careers_GM')
)
INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated,active)
SELECT slug,name,careers_url,careers_url,'PUBLIC_ATS_API',true,true FROM sources s
WHERE NOT EXISTS (SELECT 1 FROM companies c WHERE lower(c.name)=lower(s.name))
ON CONFLICT (slug) DO UPDATE SET careers_url=excluded.careers_url,source_type='PUBLIC_ATS_API',automated=true,active=true;

WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('comcast-workday','Comcast','https://comcast.wd5.myworkdayjobs.com/en-US/Comcast_Careers','comcast|Comcast_Careers'),
 ('dynata-workday','Dynata','https://dynata.wd108.myworkdayjobs.com/en-US/careers','dynata|careers'),
 ('vanguard-workday','Vanguard','https://vanguard.wd5.myworkdayjobs.com/en-US/vanguard_external','vanguard|vanguard_external'),
 ('guardian-life-workday','Guardian Life','https://guardianlife.wd5.myworkdayjobs.com/en-US/Guardian-Life-Careers','guardianlife|Guardian-Life-Careers'),
 ('accenture-workday','Accenture','https://accenture.wd103.myworkdayjobs.com/en-US/AccentureCareers','accenture|AccentureCareers'),
 ('acquire-workday','Acquire','https://acquireai.wd102.myworkdayjobs.com/en-US/Acquire','acquireai|Acquire'),
 ('booz-allen-workday','Booz Allen Hamilton','https://bah.wd1.myworkdayjobs.com/en-US/BAH_Jobs','bah|BAH_Jobs'),
 ('corebridge-workday','Corebridge Financial','https://corebridgefinancial.wd1.myworkdayjobs.com/en-US/CorebridgeFinancial','corebridgefinancial|CorebridgeFinancial'),
 ('general-motors-workday','General Motors','https://generalmotors.wd5.myworkdayjobs.com/en-US/Careers_GM','generalmotors|Careers_GM')
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
