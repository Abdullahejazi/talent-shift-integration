import { chromium } from 'playwright';

async function scrape() {
    const url = "https://www.aramco.com/en/careers";
    console.log(`Scraping target: ${url}`);
    
    try {
        const jinaUrl = `https://r.jina.ai/${url}`;
        const response = await fetch(jinaUrl);
        const textContent = await response.text();
        
        const fs = await import('fs');
        fs.writeFileSync('aramco_raw.txt', textContent);
        console.log(`Saved ${textContent.length} characters of raw text to aramco_raw.txt`);
        
    } catch (e) {
        console.error("Error during scrape:", e);
    }
}

scrape();
