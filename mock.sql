DO $$ 
DECLARE
    cat_id int;
    source_id uuid;
    i int;
BEGIN
    FOR cat_id IN 2..18 LOOP
        source_id := gen_random_uuid();
        
        INSERT INTO canonical_source (id, display_name, normalized_url, official_domain, source_type, active, consecutive_check_failures)
        VALUES (
            source_id,
            'Mock Source for Category ' || cat_id,
            'https://mocksource' || cat_id || '.com',
            'mocksource' || cat_id || '.com',
            cat_id,
            true,
            0
        );

        FOR i IN 1..5 LOOP
            INSERT INTO canonical_job (id, canonical_source_id, title, organization_name, location, fingerprint, active)
            VALUES (
                gen_random_uuid(),
                source_id,
                'Mock Job ' || i || ' for Category ' || cat_id,
                'Mock Org ' || cat_id,
                'Riyadh',
                rpad(md5(random()::text), 64, '0'),
                true
            );
        END LOOP;
    END LOOP;
END $$;
