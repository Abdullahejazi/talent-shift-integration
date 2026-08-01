ALTER TABLE job_sources DROP CONSTRAINT job_sources_type_check;
ALTER TABLE job_sources ADD CONSTRAINT job_sources_type_check CHECK (source_type IN
 ('GREENHOUSE','LEVER','ASHBY','SMARTRECRUITERS','WORKDAY','JSON_LD','RSS','GENERIC_HTML','BROWSER_FALLBACK'));

WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('fragomen-workday','Fragomen','https://fragomen.wd115.myworkdayjobs.com/en-US/FragomenCareers','fragomen|FragomenCareers'),
 ('rtx-workday','RTX','https://globalhr.wd5.myworkdayjobs.com/rec_rtx_ext_gateway','globalhr|rec_rtx_ext_gateway'),
 ('concentrix-workday','Concentrix','https://cnx.wd1.myworkdayjobs.com/en-US/external_global','cnx|external_global'),
 ('fedex-workday','FedEx','https://fedex.wd1.myworkdayjobs.com/en-US/FXE-MEISA-External','fedex|FXE-MEISA-External'),
 ('bsi-workday','BSI','https://bsigroup.wd3.myworkdayjobs.com/en-US/BSI_Careers','bsigroup|BSI_Careers'),
 ('gsk-workday','GSK','https://gsk.wd5.myworkdayjobs.com/en-US/GSKCareers','gsk|GSKCareers'),
 ('globalblue-workday','Global Blue','https://globalblue.wd3.myworkdayjobs.com/External','globalblue|External'),
 ('gilead-workday','Gilead Sciences','https://gilead.wd1.myworkdayjobs.com/gileadcareers','gilead|gileadcareers')
)
INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated,active)
SELECT slug,name,careers_url,careers_url,'PUBLIC_ATS_API',true,true FROM sources s
WHERE NOT EXISTS (SELECT 1 FROM companies c WHERE lower(c.name)=lower(s.name))
 ON CONFLICT (slug) DO UPDATE SET careers_url=excluded.careers_url,source_type='PUBLIC_ATS_API',automated=true,active=true
;

WITH sources(slug,name,careers_url,board_identifier) AS (VALUES
 ('fragomen-workday','Fragomen','https://fragomen.wd115.myworkdayjobs.com/en-US/FragomenCareers','fragomen|FragomenCareers'),
 ('rtx-workday','RTX','https://globalhr.wd5.myworkdayjobs.com/rec_rtx_ext_gateway','globalhr|rec_rtx_ext_gateway'),
 ('concentrix-workday','Concentrix','https://cnx.wd1.myworkdayjobs.com/en-US/external_global','cnx|external_global'),
 ('fedex-workday','FedEx','https://fedex.wd1.myworkdayjobs.com/en-US/FXE-MEISA-External','fedex|FXE-MEISA-External'),
 ('bsi-workday','BSI','https://bsigroup.wd3.myworkdayjobs.com/en-US/BSI_Careers','bsigroup|BSI_Careers'),
 ('gsk-workday','GSK','https://gsk.wd5.myworkdayjobs.com/en-US/GSKCareers','gsk|GSKCareers'),
 ('globalblue-workday','Global Blue','https://globalblue.wd3.myworkdayjobs.com/External','globalblue|External'),
 ('gilead-workday','Gilead Sciences','https://gilead.wd1.myworkdayjobs.com/gileadcareers','gilead|gileadcareers')
)
INSERT INTO job_sources(company_id,company_name,careers_url,source_type,ats_provider,board_identifier,country,enabled,permission_status,refresh_interval_minutes,next_retry_at)
SELECT c.id,s.name,s.careers_url,'WORKDAY','WORKDAY',s.board_identifier,'SA',true,'PUBLIC_ALLOWED',10,now()
FROM sources s JOIN LATERAL (
 SELECT id FROM companies WHERE slug=s.slug OR lower(name)=lower(s.name)
 ORDER BY (slug=s.slug) DESC LIMIT 1
) c ON true
ON CONFLICT (lower(careers_url)) DO UPDATE SET source_type='WORKDAY',ats_provider='WORKDAY',board_identifier=excluded.board_identifier,
 enabled=true,permission_status='PUBLIC_ALLOWED',refresh_interval_minutes=10,next_retry_at=now(),updated_at=now();
