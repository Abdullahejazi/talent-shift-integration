package com.talentshift.hub.integration.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SemanticSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);
    private final JdbcTemplate db;

    public SemanticSearchService(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * Maps a concept text to a 3-dimensional vector [Tech, Culinary, Management]
     */
    public String getVectorForText(String text) {
        if (text == null) return "[0.1, 0.1, 0.1]";
        
        String lower = text.toLowerCase();
        
        // Dimension 1: Tech / Engineering
        boolean isTech = lower.contains("software") || lower.contains("engineer") || 
                         lower.contains("developer") || lower.contains("it") || 
                         lower.contains("data") || lower.contains("backend") || lower.contains("frontend");
        
        // Dimension 2: Culinary / Restaurant
        boolean isCulinary = lower.contains("chef") || lower.contains("cook") || 
                             lower.contains("restaurant") || lower.contains("food") || 
                             lower.contains("kitchen") || lower.contains("baker");
                             
        // Dimension 3: Management / Business
        boolean isManagement = lower.contains("manager") || lower.contains("admin") || 
                               lower.contains("director") || lower.contains("executive") || 
                               lower.contains("business") || lower.contains("sales");

        double techScore = isTech ? 1.0 : 0.0;
        double culScore = isCulinary ? 1.0 : 0.0;
        double manScore = isManagement ? 1.0 : 0.0;
        
        // Default to a small magnitude if nothing matches so it doesn't break math
        if (!isTech && !isCulinary && !isManagement) {
            return "[0.1, 0.1, 0.1]";
        }
        
        return String.format("[%f, %f, %f]", techScore, culScore, manScore);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void populateMissingVectors() {
        log.info("Populating missing vectors for semantic search...");
        List<Map<String, Object>> jobs = db.queryForList("SELECT id, title FROM canonical_job WHERE title_vector IS NULL");
        for (Map<String, Object> job : jobs) {
            String vector = getVectorForText((String) job.get("title"));
            db.update("UPDATE canonical_job SET title_vector = ?::vector WHERE id = ?", vector, job.get("id"));
        }
        log.info("Finished populating {} vectors.", jobs.size());
    }

    public List<Map<String, Object>> search(String query, int limit) {
        String queryVector = getVectorForText(query);
        
        // Use pgvector's L2 distance operator (<->) to find the closest semantic matches.
        // We also apply a basic text fallback to ensure exact matches are boosted if needed,
        // but for this demo, vector distance is the primary sort.
        String sql = """
            SELECT id, title, organization_name, location, employment_type, normalized_job_url, created_at, active
            FROM canonical_job
            WHERE active = true
            ORDER BY title_vector <-> ?::vector, similarity(lower(title), lower(?)) DESC
            LIMIT ?
        """;
        
        return db.queryForList(sql, queryVector, query, limit);
    }
}
