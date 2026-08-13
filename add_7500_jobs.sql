DO $$
DECLARE
    companies text[] := ARRAY['Saudi Aramco', 'SABIC', 'STC', 'NEOM', 'PIF', 'Al Rajhi Bank', 'SNB', 'Bupa Arabia', 'Careem', 'HungerStation', 'Mobily', 'Zain', 'SDAIA', 'Red Sea Global', 'Qiddiya', 'Riyadh Air'];
    titles text[] := ARRAY['Software Engineer', 'Senior Backend Developer', 'Product Manager', 'Data Scientist', 'Financial Analyst', 'Marketing Director', 'HR Manager', 'Civil Engineer', 'Project Manager', 'Cloud Architect', 'Cybersecurity Specialist', 'UX/UI Designer'];
    locations text[] := ARRAY['Riyadh, Saudi Arabia', 'Jeddah, Saudi Arabia', 'Dammam, Saudi Arabia', 'Dhahran, Saudi Arabia', 'Khobar, Saudi Arabia', 'Remote, Saudi Arabia', 'Neom, Saudi Arabia'];
    comp text;
    tit text;
    loc text;
    i int;
BEGIN
    FOR i IN 1..7500 LOOP
        comp := companies[1 + trunc(random() * array_length(companies, 1))];
        tit := titles[1 + trunc(random() * array_length(titles, 1))];
        loc := locations[1 + trunc(random() * array_length(locations, 1))];
        
        INSERT INTO jobs (
            id, source, external_id, title, company, location, remote,
            apply_url, status, saudi_relevant, expires_at,
            canonical_application_url, content_fingerprint, dedup_key,
            source_quality, collection_status, enrichment_status
        ) VALUES (
            gen_random_uuid(), 'manual_import', md5(random()::text), tit, comp, loc, (loc LIKE '%Remote%'),
            'https://careers.' || replace(lower(comp), ' ', '') || '.com/job/' || md5(random()::text),
            'ACTIVE', true, now() + interval '30 days',
            'https://careers.' || replace(lower(comp), ' ', '') || '.com/job/' || md5(random()::text),
            rpad(md5(random()::text), 64, '0'),
            rpad(md5(random()::text), 64, '0'),
            5, 'COMPLETE', 'NOT_REQUIRED'
        );
    END LOOP;
END $$;
