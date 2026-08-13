import { chromium } from 'playwright';

async function scrape() {
    const url = "https://boards.greenhouse.io/tamara";
    console.log(`Scraping target: ${url}`);
    
    try {
        const browser = await chromium.launch({ headless: true });
        const context = await browser.newContext({
            userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            viewport: { width: 1920, height: 1080 }
        });
        const page = await context.newPage();
        
        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
        await page.waitForTimeout(3000); // Wait for dynamic content
        
        // Remove scripts/styles
        await page.evaluate(() => {
            document.querySelectorAll('script, style, noscript, iframe').forEach(el => el.remove());
        });
        
        const textContent = await page.evaluate(() => document.body.innerText);
        await browser.close();
        
        const fs = await import('fs');
        fs.writeFileSync('aramco_raw.txt', textContent);
        console.log(`Saved ${textContent.length} characters of raw text to aramco_raw.txt`);
        
    } catch (e) {
        console.error("Error during scrape:", e);
    }
}

scrape();
