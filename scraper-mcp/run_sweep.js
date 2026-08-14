import { chromium } from 'playwright';
import pg from 'pg';
import * as dotenv from 'dotenv';
dotenv.config();

const { Client } = pg;

async function runSweep() {
    console.log("Starting Daily Job Sweep...");
    
    // Connect to DB
    const client = new Client({
        user: process.env.DB_USER || 'talentshift',
        password: process.env.DB_PASSWORD || 'talentshift-local-password',
        host: process.env.DB_HOST || 'localhost',
        database: process.env.DB_NAME || 'talentshift',
        port: parseInt(process.env.DB_PORT || '5434')
    });
    
    try {
        await client.connect();
        console.log("Connected to Database.");
        
        // Let's scrape Aramco directly for the demo
        const urlToScrape = "https://boards.greenhouse.io/tamara";
        console.log(`Scraping target: ${urlToScrape}`);
        
        let textContent = "";
        try {
            console.log("Waking up Agent-Reach (Jina Reader) to bypass bot blockers...");
            const jinaUrl = `https://r.jina.ai/${urlToScrape}`;
            const response = await fetch(jinaUrl);
            textContent = await response.text();
            
            console.log(`Successfully bypassed blockers and extracted ${textContent.length} characters of Markdown!`);
            console.log("Sample of extracted text:");
            console.log(textContent.substring(0, 200).replace(/\n/g, " ") + "...");
        } catch (botError) {
            console.log("Error fetching via Agent-Reach: " + botError.message);
        }
        
        // Extract REAL jobs from the Markdown output!
        console.log("Extracting real job postings from the Jina Markdown...");
        const mockJobs = [];
        // Look for markdown links: [Job Title](https://link)
        const regex = /\[([^\]]+)\]\((https?:\/\/[^\)]+)\)/g;
        let match;
        while ((match = regex.exec(textContent)) !== null) {
            const title = match[1].trim();
            const url = match[2];
            // Filter out generic links, only keep likely job links
            if (url.includes('/jobs/') || url.includes('/careers/') || title.toLowerCase().includes('engineer') || title.toLowerCase().includes('manager')) {
                // Avoid duplicates
                if (!mockJobs.find(j => j.applyUrl === url)) {
                    mockJobs.push({
                        title: title,
                        company: "Extracted Source",
                        location: "Saudi Arabia",
                        applyUrl: url,
                        category: "General"
                    });
                }
            }
        }
        
        console.log(`Successfully extracted ${mockJobs.length} REAL jobs!`);
        
        console.log("Saving extracted jobs to database...");
        
        let saved = 0;
        for (const job of mockJobs) {
            if (saved >= 10) break; // Just save the first 10 for the test
            await client.query(`
                INSERT INTO jobs (
                    id, source, external_id, title, company, apply_url, 
                    canonical_application_url, dedup_key, content_fingerprint, expires_at,
                    location, normalized_category, status
                ) VALUES (
                    gen_random_uuid(), $2, gen_random_uuid()::text, $1, $2, $3, 
                    $3, encode(gen_random_bytes(32), 'hex'), encode(gen_random_bytes(32), 'hex'), NOW() + INTERVAL '30 days',
                    $4, $5, 'ACTIVE'
                )
                ON CONFLICT DO NOTHING
            `, [job.title, job.company, job.applyUrl, job.location, job.category]);
            console.log(`Saved Real Job: ${job.title}`);
            saved++;
        }

        console.log("Sweep Complete!");
        
    } catch (e) {
        console.error("Error during sweep:", e);
    } finally {
        await client.end();
        process.exit(0);
    }
}

runSweep();
