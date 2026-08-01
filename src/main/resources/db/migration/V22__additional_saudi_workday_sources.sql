WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('air-liquide-workday','Air Liquide','https://airliquidehr.wd3.myworkdayjobs.com/en-CA/AirLiquideExternalCareer','airliquidehr|AirLiquideExternalCareer'),
 ('aggreko-workday','Aggreko','https://aggreko.wd3.myworkdayjobs.com/en-US/Aggreko_Careers_1','aggreko|Aggreko_Careers_1'),
 ('img-workday','IMG','https://wwecorp.wd5.myworkdayjobs.com/en-US/img','wwecorp|img'),
 ('lilly-workday','Eli Lilly','https://lilly.wd5.myworkdayjobs.com/en-US/LLY','lilly|LLY'),
 ('stanley-black-decker-workday','Stanley Black and Decker','https://sbdinc.wd1.myworkdayjobs.com/en-US/Stanley_Black_Decker_Career_Site','sbdinc|Stanley_Black_Decker_Career_Site'),
 ('simcorp-workday','SimCorp','https://simcorp.wd3.myworkdayjobs.com/SimCorp_Jobs','simcorp|SimCorp_Jobs'),
 ('alcon-workday','Alcon','https://alcon.wd5.myworkdayjobs.com/en-US/careers_alcon','alcon|careers_alcon'),
 ('surbana-jurong-workday','Surbana Jurong','https://surbanajurong.wd3.myworkdayjobs.com/en-US/SJ_Careers','surbanajurong|SJ_Careers')
)
INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated,active)
SELECT slug,name,careers_url,careers_url,'PUBLIC_ATS_API',true,true FROM sources s
WHERE NOT EXISTS (SELECT 1 FROM companies c WHERE lower(c.name)=lower(s.name))
ON CONFLICT (slug) DO UPDATE SET careers_url=excluded.careers_url,source_type='PUBLIC_ATS_API',automated=true,active=true;

WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('air-liquide-workday','Air Liquide','https://airliquidehr.wd3.myworkdayjobs.com/en-CA/AirLiquideExternalCareer','airliquidehr|AirLiquideExternalCareer'),
 ('aggreko-workday','Aggreko','https://aggreko.wd3.myworkdayjobs.com/en-US/Aggreko_Careers_1','aggreko|Aggreko_Careers_1'),
 ('img-workday','IMG','https://wwecorp.wd5.myworkdayjobs.com/en-US/img','wwecorp|img'),
 ('lilly-workday','Eli Lilly','https://lilly.wd5.myworkdayjobs.com/en-US/LLY','lilly|LLY'),
 ('stanley-black-decker-workday','Stanley Black and Decker','https://sbdinc.wd1.myworkdayjobs.com/en-US/Stanley_Black_Decker_Career_Site','sbdinc|Stanley_Black_Decker_Career_Site'),
 ('simcorp-workday','SimCorp','https://simcorp.wd3.myworkdayjobs.com/SimCorp_Jobs','simcorp|SimCorp_Jobs'),
 ('alcon-workday','Alcon','https://alcon.wd5.myworkdayjobs.com/en-US/careers_alcon','alcon|careers_alcon'),
 ('surbana-jurong-workday','Surbana Jurong','https://surbanajurong.wd3.myworkdayjobs.com/en-US/SJ_Careers','surbanajurong|SJ_Careers')
)
INSERT INTO job_sources(company_id,company_name,careers_url,source_type,ats_provider,board_identifier,country,enabled,permission_status,refresh_interval_minutes,next_retry_at)
SELECT c.id,s.name,s.careers_url,'WORKDAY','WORKDAY',s.board_identifier,'SA',true,'PUBLIC_ALLOWED',10,now()
FROM sources s JOIN LATERAL (
 SELECT id FROM companies WHERE slug=s.slug OR lower(name)=lower(s.name)
 ORDER BY (slug=s.slug) DESC LIMIT 1
) c ON true
ON CONFLICT (lower(careers_url)) DO UPDATE SET source_type='WORKDAY',ats_provider='WORKDAY',board_identifier=excluded.board_identifier,
 enabled=true,permission_status='PUBLIC_ALLOWED',refresh_interval_minutes=10,next_retry_at=now(),updated_at=now();
