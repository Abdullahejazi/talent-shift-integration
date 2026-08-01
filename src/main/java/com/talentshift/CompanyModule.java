package com.talentshift;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

record CompanyView(UUID id, String slug, String name, String websiteUrl, String careersUrl,
        String sourceType, boolean automated, long activeJobCount) {}

record CompanyPage(List<CompanyView> items, long total, int page, int size) {}

@Repository
class CompanyRepository {
    private final JdbcClient jdbc;

    CompanyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    CompanyPage search(String keyword, int page, int size) {
        String normalized = keyword == null ? "" : keyword.trim();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String where = "FROM companies c WHERE c.active=true AND (:keyword='' OR c.name ILIKE :keywordLike)";
        long total = jdbc.sql("SELECT count(*) " + where)
                .param("keyword", normalized)
                .param("keywordLike", "%" + normalized + "%")
                .query(Long.class).single();
        List<CompanyView> items = jdbc.sql("""
                SELECT c.id, c.slug, c.name, c.website_url, c.careers_url, c.source_type, c.automated,
                       (SELECT count(*) FROM jobs j
                         WHERE j.status='ACTIVE' AND j.saudi_relevant=true
                           AND lower(j.company)=lower(c.name)) AS active_job_count
                """ + where + " ORDER BY active_job_count DESC, c.name LIMIT :limit OFFSET :offset")
                .param("keyword", normalized)
                .param("keywordLike", "%" + normalized + "%")
                .param("limit", safeSize)
                .param("offset", safePage * safeSize)
                .query(CompanyRepository::map).list();
        return new CompanyPage(items, total, safePage, safeSize);
    }

    Optional<CompanyView> find(String slug) {
        return jdbc.sql("""
                SELECT c.id, c.slug, c.name, c.website_url, c.careers_url, c.source_type, c.automated,
                       (SELECT count(*) FROM jobs j
                         WHERE j.status='ACTIVE' AND j.saudi_relevant=true
                           AND lower(j.company)=lower(c.name)) AS active_job_count
                FROM companies c WHERE c.slug=:slug AND c.active=true
                """).param("slug", slug).query(CompanyRepository::map).optional();
    }

    private static CompanyView map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CompanyView(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                rs.getString("website_url"), rs.getString("careers_url"), rs.getString("source_type"),
                rs.getBoolean("automated"), rs.getLong("active_job_count"));
    }
}

@RestController
@RequestMapping("/api/companies")
class CompanyController {
    private final CompanyRepository companies;

    CompanyController(CompanyRepository companies) {
        this.companies = companies;
    }

    @GetMapping
    CompanyPage companies(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size) {
        return companies.search(keyword, page, size);
    }

    @GetMapping("/{slug:[a-z0-9-]+}")
    CompanyView company(@PathVariable String slug) {
        return companies.find(slug).orElseThrow(CompanyNotFoundException::new);
    }
}

class CompanyNotFoundException extends RuntimeException {}
