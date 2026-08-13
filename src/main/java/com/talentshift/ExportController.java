package com.talentshift;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/export")
public class ExportController {

    private final JdbcClient jdbc;
    
    // We expect the Origin system to send this secret key
    private static final String SYSTEM_KEY = "ts-origin-secure-key-2026";

    public ExportController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> exportJobs(
            @RequestHeader(value = "X-System-Key", required = false) String systemKey) {
        
        // 1. Authenticate the Origin website
        if (systemKey == null || !systemKey.equals(SYSTEM_KEY)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing X-System-Key");
        }

        // 2. Fetch all jobs for the Origin system. 
        // As discussed, we omit the 'category' since Origin manages its own display logic.
        return jdbc.sql("""
                SELECT 
                    id, 
                    title, 
                    company, 
                    location, 
                    employment_type, 
                    remote, 
                    apply_url, 
                    source,
                    posted_at
                FROM jobs 
                ORDER BY posted_at DESC NULLS LAST
                """)
                .query()
                .listOfRows();
    }
}
