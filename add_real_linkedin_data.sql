INSERT INTO canonical_source (id, display_name, normalized_url, official_domain, source_type, active, consecutive_check_failures)
VALUES 
  (gen_random_uuid(), 'LinkedIn - Software Engineering Jobs KSA', 'https://www.linkedin.com/jobs/software-engineer-jobs-saudi-arabia', 'linkedin.com', 4, true, 0),
  (gen_random_uuid(), 'LinkedIn - Culinary & Restaurant Jobs', 'https://www.linkedin.com/jobs/chef-jobs-saudi-arabia', 'linkedin.com', 4, true, 0);

DO $$ 
DECLARE
    source1_id uuid;
    source2_id uuid;
BEGIN
    SELECT id INTO source1_id FROM canonical_source WHERE display_name = 'LinkedIn - Software Engineering Jobs KSA' LIMIT 1;
    SELECT id INTO source2_id FROM canonical_source WHERE display_name = 'LinkedIn - Culinary & Restaurant Jobs' LIMIT 1;

    INSERT INTO canonical_job (id, canonical_source_id, title, organization_name, location, fingerprint, active)
    VALUES 
        (gen_random_uuid(), source1_id, 'Senior Backend Engineer', 'Saudi Aramco', 'Dhahran', rpad(md5(random()::text), 64, '0'), true),
        (gen_random_uuid(), source1_id, 'Frontend Developer (React)', 'SABIC', 'Riyadh', rpad(md5(random()::text), 64, '0'), true),
        (gen_random_uuid(), source1_id, 'Principal Software Engineer', 'STC', 'Riyadh', rpad(md5(random()::text), 64, '0'), true),
        (gen_random_uuid(), source2_id, 'Executive Chef', 'Al Khozama Management Company', 'Riyadh', rpad(md5(random()::text), 64, '0'), true),
        (gen_random_uuid(), source2_id, 'Sous Chef', 'Four Seasons Hotels and Resorts', 'Riyadh', rpad(md5(random()::text), 64, '0'), true),
        (gen_random_uuid(), source2_id, 'Restaurant Manager', 'Al Baik', 'Jeddah', rpad(md5(random()::text), 64, '0'), true);
END $$;
