WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('pitney-bowes-workday','Pitney Bowes','https://pitneybowes.wd1.myworkdayjobs.com/en-US/PBCareers','pitneybowes|PBCareers'),
 ('embry-riddle-workday','Embry-Riddle Aeronautical University','https://embryriddle.wd1.myworkdayjobs.com/en-US/External','embryriddle|External'),
 ('allstate-workday','Allstate','https://allstate.wd5.myworkdayjobs.com/en-US/allstate_careers','allstate|allstate_careers'),
 ('syneos-health-workday','Syneos Health','https://syneoshealth.wd12.myworkdayjobs.com/en-US/Syneos_Health_External_Site','syneoshealth|Syneos_Health_External_Site'),
 ('regeneron-workday','Regeneron','https://regeneron.wd1.myworkdayjobs.com/en-US/Careers','regeneron|Careers'),
 ('medical-college-wisconsin-workday','Medical College of Wisconsin','https://mcw.wd503.myworkdayjobs.com/en-US/ExternalCareers','mcw|ExternalCareers'),
 ('availity-workday','Availity','https://availity.wd1.myworkdayjobs.com/en-US/Availity_Careers_US','availity|Availity_Careers_US'),
 ('cushman-wakefield-workday','Cushman and Wakefield','https://cw.wd1.myworkdayjobs.com/en-US/External','cw|External'),
 ('icf-workday','ICF','https://icf.wd5.myworkdayjobs.com/en-US/ICFExternal_Career_Site','icf|ICFExternal_Career_Site'),
 ('ancestry-workday','Ancestry','https://ancestry.wd501.myworkdayjobs.com/en-US/Careers','ancestry|Careers')
)
INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated,active)
SELECT slug,name,careers_url,careers_url,'PUBLIC_ATS_API',true,true FROM sources s
WHERE NOT EXISTS (SELECT 1 FROM companies c WHERE lower(c.name)=lower(s.name))
ON CONFLICT (slug) DO UPDATE SET careers_url=excluded.careers_url,source_type='PUBLIC_ATS_API',automated=true,active=true;

WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('pitney-bowes-workday','Pitney Bowes','https://pitneybowes.wd1.myworkdayjobs.com/en-US/PBCareers','pitneybowes|PBCareers'),
 ('embry-riddle-workday','Embry-Riddle Aeronautical University','https://embryriddle.wd1.myworkdayjobs.com/en-US/External','embryriddle|External'),
 ('allstate-workday','Allstate','https://allstate.wd5.myworkdayjobs.com/en-US/allstate_careers','allstate|allstate_careers'),
 ('syneos-health-workday','Syneos Health','https://syneoshealth.wd12.myworkdayjobs.com/en-US/Syneos_Health_External_Site','syneoshealth|Syneos_Health_External_Site'),
 ('regeneron-workday','Regeneron','https://regeneron.wd1.myworkdayjobs.com/en-US/Careers','regeneron|Careers'),
 ('medical-college-wisconsin-workday','Medical College of Wisconsin','https://mcw.wd503.myworkdayjobs.com/en-US/ExternalCareers','mcw|ExternalCareers'),
 ('availity-workday','Availity','https://availity.wd1.myworkdayjobs.com/en-US/Availity_Careers_US','availity|Availity_Careers_US'),
 ('cushman-wakefield-workday','Cushman and Wakefield','https://cw.wd1.myworkdayjobs.com/en-US/External','cw|External'),
 ('icf-workday','ICF','https://icf.wd5.myworkdayjobs.com/en-US/ICFExternal_Career_Site','icf|ICFExternal_Career_Site'),
 ('ancestry-workday','Ancestry','https://ancestry.wd501.myworkdayjobs.com/en-US/Careers','ancestry|Careers')
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
