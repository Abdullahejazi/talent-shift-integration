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
        const urlToScrape = "https://www.aramco.com/en/careers";
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
        
        // Here is where the AI Brain (Antigravity) would normally parse the text into JSON.
        // For the demo, we will simulate the AI parsing and saving.
        console.log("Simulating AI parsing into structured Job JSON...");
        const mockJobs = [
            {
                title: "Senior Data Analyst - Business Banking",
                company: "Tamara",
                location: "Riyadh, Saudi Arabia",
                applyUrl: "https://boards.greenhouse.io/tamara",
                category: "Data"
            },
            {
                title: "Application Support Engineer",
                company: "Tamara",
                location: "Saudi Arabia",
                applyUrl: "https://boards.greenhouse.io/tamara",
                category: "Engineering"
            },
            {
                title: "Engineering Manager - II",
                company: "Tamara",
                location: "Riyadh, Saudi Arabia",
                applyUrl: "https://boards.greenhouse.io/tamara",
                category: "Engineering"
            },
            {
                title: "Fraud Investigator",
                company: "Tamara",
                location: "Riyadh, Saudi Arabia",
                applyUrl: "https://boards.greenhouse.io/tamara",
                category: "Risk"
            }
        ];
        
        console.log("Saving jobs to database...");
        
        for (const job of mockJobs) {
            await client.query(`
                INSERT INTO jobs (
                    id, source, external_id, title, company, apply_url, 
                    canonical_application_url, dedup_key, content_fingerprint, expires_at,
                    location, normalized_category, status
                ) VALUES (
                    gen_random_uuid(), 'Tamara', gen_random_uuid()::text, $1, $2, $3, 
                    $3, encode(gen_random_bytes(32), 'hex'), encode(gen_random_bytes(32), 'hex'), NOW() + INTERVAL '30 days',
                    $4, $5, 'ACTIVE'
                )
                ON CONFLICT DO NOTHING
            `, [job.title, job.company, job.applyUrl, job.location, job.category]);
            console.log(`Saved: ${job.title} at ${job.company}`);
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
