DO $$ 
DECLARE
    cat_id int;
    source_id uuid;
    i int;
    j int;
    num_sources int;
    num_jobs int;
BEGIN
    FOR cat_id IN 2..18 LOOP
        -- Generate between 3 and 14 more sources for this category to make it look realistic
        num_sources := floor(random() * 12 + 3)::int;
        
        FOR i IN 1..num_sources LOOP
            source_id := gen_random_uuid();
            
            INSERT INTO canonical_source (id, display_name, normalized_url, official_domain, source_type, active, consecutive_check_failures)
            VALUES (
                source_id,
                'Mock Source ' || i || ' for Category ' || cat_id,
                'https://mocksource' || cat_id || '_' || i || '.com',
                'mocksource' || cat_id || '_' || i || '.com',
                cat_id,
                true,
                0
            );

            num_jobs := floor(random() * 8 + 2)::int; -- 2 to 9 jobs per source
            FOR j IN 1..num_jobs LOOP
                INSERT INTO canonical_job (id, canonical_source_id, title, organization_name, location, fingerprint, active)
                VALUES (
                    gen_random_uuid(),
                    source_id,
                    'Mock Job ' || j || ' for Source ' || i || ' Category ' || cat_id,
                    'Mock Org ' || cat_id || '_' || i,
                    'Riyadh',
                    rpad(md5(random()::text), 64, '0'),
                    true
                );
            END LOOP;
        END LOOP;
    END LOOP;
END $$;
