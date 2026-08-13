package com.talentshift;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/sources")
public class AdminSourceController {

    private final JdbcClient jdbc;

    public AdminSourceController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/approve")
    public Map<String, Object> approveSource(@RequestBody Map<String, String> payload) {
        String url = payload.get("url");
        String organization = payload.get("organization");
        
        if (url == null || organization == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing url or organization");
        }

        // Just pick the first category ID as a fallback for the demo
        UUID categoryId = jdbc.sql("SELECT id FROM source_categories LIMIT 1")
                .query(UUID.class)
                .optional()
                .orElse(null);

        // Ensure URL has http scheme for careers_url
        if (!url.startsWith("http")) {
            url = "https://" + url;
        }

        // Insert into job_sources
        jdbc.sql("""
            INSERT INTO job_sources (
                company_name, 
                careers_url, 
                category_id, 
                source_type, 
                country, 
                enabled, 
                permission_status
            ) VALUES (
                :companyName, 
                :careersUrl, 
                :categoryId, 
                'GENERIC_HTML', 
                'SA', 
                TRUE, 
                'PUBLIC_ALLOWED'
            )
            ON CONFLICT (lower(careers_url)) DO NOTHING
            """)
            .param("companyName", organization)
            .param("careersUrl", url)
            .param("categoryId", categoryId)
            .update();

        return Map.of("success", true, "message", "Source approved and saved permanently.");
    }
}
