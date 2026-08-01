package com.talentshift;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class SeedCandidateImportConfiguration {
    @Bean
    ApplicationRunner importSeedCandidates(JdbcClient jdbc,
            @Value("${app.jobs.seed-candidate-csv:}") String csvPath) {
        return args -> {
            if (csvPath == null || csvPath.isBlank()) return;
            Path path = Path.of(csvPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) return;
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Iterable<CSVRecord> rows = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(true).setTrim(true).get().parse(reader);
                for (CSVRecord row : rows) {
                    String organization = first(row, "organization_name", "company_name");
                    String sourceUrl = first(row, "job_source_url", "careers_url", "website_url");
                    if (organization.isBlank() || sourceUrl.isBlank()) continue;
                    jdbc.sql("""
                            INSERT INTO seed_candidates(external_seed_id,organization_name,official_domain,source_url,
                              source_type,employer_search_query,country_code,permission_status,verification_status,
                              terms_match_status,enabled)
                            VALUES (:seed,:organization,:domain,:url,:type,:query,'SA',:permission,:verification,:terms,false)
                            ON CONFLICT (lower(organization_name),lower(coalesce(official_domain,''))) DO UPDATE SET
                              external_seed_id=EXCLUDED.external_seed_id,
                              source_url=EXCLUDED.source_url,source_type=EXCLUDED.source_type,
                              employer_search_query=EXCLUDED.employer_search_query,
                              permission_status=EXCLUDED.permission_status,
                              verification_status=EXCLUDED.verification_status,terms_match_status=EXCLUDED.terms_match_status
                            """).param("seed", value(row,"seed_id")).param("organization",organization)
                            .param("domain", blankToNull(first(row,"official_domain","allowed_domain"))).param("url",sourceUrl)
                            .param("type",defaultValue(first(row,"source_type","source_kind"),"COMPANY_DOMAIN_DISCOVERY"))
                            .param("query",blankToNull(first(row,"employer_search_query","normalized_company_name")))
                            .param("permission",defaultValue(value(row,"permission_status"),"NOT_YET_APPROVED"))
                            .param("verification",defaultValue(value(row,"verification_status"),"PENDING_VERIFICATION"))
                            .param("terms",defaultValue(value(row,"terms_match_status"),"PENDING_TERMS_REVIEW")).update();
                    if (verifiedForBackend(row)) promoteVerifiedSource(jdbc,row,organization,sourceUrl);
                }
            }
        };
    }

    private static boolean verifiedForBackend(CSVRecord row) {
        String permission=value(row,"permission_status"), verification=value(row,"verification_status");
        return (permission.equals("PUBLIC_ALLOWED")||permission.equals("API_LICENSED"))
                && verification.equals("VERIFIED_ACTIVE")
                && Boolean.parseBoolean(value(row,"direct_apply_verified"));
    }

    private static void promoteVerifiedSource(JdbcClient jdbc,CSVRecord row,String organization,String sourceUrl) {
        String provider=value(row,"ats_provider").toUpperCase(Locale.ROOT);
        String type=switch(provider){case "GREENHOUSE","LEVER","ASHBY","SMARTRECRUITERS"->provider;default->"GENERIC_HTML";};
        String board=blankToNull(value(row,"board_identifier"));
        UUID companyId=jdbc.sql("""
                SELECT id FROM companies WHERE lower(careers_url)=lower(:url) OR lower(name)=lower(:name)
                ORDER BY CASE WHEN lower(careers_url)=lower(:url) THEN 0 ELSE 1 END LIMIT 1
                """).param("url",sourceUrl).param("name",organization).query(UUID.class).optional().orElse(null);
        if(companyId==null){
            String normalized=defaultValue(value(row,"normalized_company_name"),organization);
            String slug=normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("(^-|-$)","");
            if(slug.isBlank())slug="company";
            companyId=jdbc.sql("""
                    INSERT INTO companies(slug,name,website_url,careers_url,source_type,automated,active)
                    VALUES (:slug,:name,:website,:careers,'PUBLIC_ATS_API',true,true)
                    ON CONFLICT (slug) DO UPDATE SET careers_url=EXCLUDED.careers_url,
                      website_url=EXCLUDED.website_url,source_type='PUBLIC_ATS_API',automated=true,active=true
                    RETURNING id
                    """).param("slug",slug+"-"+AuthService.hash(sourceUrl).substring(0,8)).param("name",organization)
                    .param("website",defaultValue(value(row,"website_url"),sourceUrl)).param("careers",sourceUrl)
                    .query(UUID.class).single();
        }
        jdbc.sql("""
                INSERT INTO job_sources(company_id,company_name,careers_url,source_type,ats_provider,board_identifier,
                  country,enabled,permission_status,refresh_interval_minutes,next_retry_at)
                VALUES (:company,:name,:url,:type,:provider,:board,'SA',true,:permission,10,now())
                ON CONFLICT (lower(careers_url)) DO UPDATE SET enabled=true,permission_status=EXCLUDED.permission_status,
                  source_type=EXCLUDED.source_type,ats_provider=EXCLUDED.ats_provider,
                  board_identifier=EXCLUDED.board_identifier,refresh_interval_minutes=10,next_retry_at=now(),updated_at=now()
                """).param("company",companyId).param("name",organization).param("url",sourceUrl).param("type",type)
                .param("provider",provider.isBlank()?null:provider).param("board",board)
                .param("permission",value(row,"permission_status")).update();
        jdbc.sql("""
                UPDATE seed_candidates SET enabled=true,searched_at=now() WHERE lower(source_url)=lower(:url)
                """).param("url",sourceUrl).update();
    }

    private static String value(CSVRecord row,String name){
        if(row.isMapped(name))return row.get(name).trim();
        return row.toMap().entrySet().stream()
                .filter(entry->entry.getKey().replace("\uFEFF","").equals(name))
                .map(entry->entry.getValue().trim()).findFirst().orElse("");
    }
    private static String first(CSVRecord row,String... names){for(String name:names){String value=value(row,name);if(!value.isBlank())return value;}return "";}
    private static String defaultValue(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
    private static String blankToNull(String value){return value==null||value.isBlank()?null:value;}
}
