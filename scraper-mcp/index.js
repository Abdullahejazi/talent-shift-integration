#!/usr/bin/env node

const originalWrite = process.stdout.write;
process.stdout.write = function (chunk, encoding, callback) {
  const str = chunk.toString();
  if (str.trim().startsWith('{')) {
    return originalWrite.call(process.stdout, chunk, encoding, callback);
  }
  return process.stderr.write(chunk, encoding, callback);
};

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { chromium } from "playwright";
import pkg from "pg";
const { Client } = pkg;
import fs from "fs";

if (fs.existsSync(".env")) {
  const envConfig = fs.readFileSync(".env", "utf-8");
  envConfig.split("\n").forEach(line => {
    const match = line.match(/^\s*([\w.-]+)\s*=\s*(.*)?\s*$/);
    if (match) {
      let val = match[2].trim();
      if (val.startsWith('"') && val.endsWith('"')) val = val.slice(1, -1);
      process.env[match[1]] = val;
    }
  });
}

const server = new Server(
  {
    name: "talentshift-scraper-mcp",
    version: "1.0.0",
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

async function getDbClient() {
  const client = new Client({
    user: process.env.DB_USER || "postgres",
    host: process.env.DB_HOST || "localhost",
    database: process.env.DB_NAME || "talentshift",
    password: process.env.DB_PASSWORD || "postgres",
    port: parseInt(process.env.DB_PORT || "5432"),
  });
  await client.connect();
  return client;
}

server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      {
        name: "get_pending_sources",
        description: "Fetch all active sources (companies) from the PostgreSQL database that need to be scraped.",
        inputSchema: {
          type: "object",
          properties: {},
          required: []
        }
      },
      {
        name: "scrape_website",
        description: "Open an invisible browser to visit a URL, bypass basic bot checks, and return the raw text/HTML.",
        inputSchema: {
          type: "object",
          properties: {
            url: { type: "string", description: "The URL of the careers page to scrape." },
            extractHtml: { type: "boolean", description: "If true, returns HTML. If false, returns raw text. Default is false." }
          },
          required: ["url"]
        }
      },
      {
        name: "save_parsed_jobs",
        description: "Save a list of parsed jobs directly into the PostgreSQL database. Handles duplicates automatically.",
        inputSchema: {
          type: "object",
          properties: {
            jobs: {
              type: "array",
              items: {
                type: "object",
                properties: {
                  job_title: { type: "string" },
                  company_name: { type: "string" },
                  location: { type: "string" },
                  job_url: { type: "string" },
                  source_id: { type: "string", description: "The UUID of the source" }
                },
                required: ["job_title", "company_name", "job_url"]
              }
            }
          },
          required: ["jobs"]
        }
      }
    ]
  };
});

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  if (request.params.name === "get_pending_sources") {
    const client = await getDbClient();
    try {
      const res = await client.query("SELECT id, company_name, careers_url FROM job_sources WHERE enabled = true");
      return {
        content: [{ type: "text", text: JSON.stringify(res.rows, null, 2) }]
      };
    } catch (e) {
      return { content: [{ type: "text", text: `Error: ${e.message}` }] };
    } finally {
      await client.end();
    }
  }

  if (request.params.name === "scrape_website") {
    const { url, extractHtml } = request.params.arguments;
    let browser;
    try {
      browser = await chromium.launch({ headless: true });
      const context = await browser.newContext({
        userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
      });
      const page = await context.newPage();
      
      await page.goto(url, { waitUntil: "networkidle", timeout: 30000 });
      await page.waitForTimeout(2000);
      
      await page.evaluate(() => {
        document.querySelectorAll('script, style, noscript, iframe').forEach(el => el.remove());
      });

      let result;
      if (extractHtml) {
        result = await page.content();
      } else {
        result = await page.evaluate(() => document.body.innerText);
      }
      
      if (result.length > 50000) {
         result = result.substring(0, 50000) + "... (truncated)";
      }
      
      return {
        content: [{ type: "text", text: result }]
      };
    } catch (e) {
      return { content: [{ type: "text", text: `Scraping error: ${e.message}` }] };
    } finally {
      if (browser) await browser.close();
    }
  }

  if (request.params.name === "save_parsed_jobs") {
    const { jobs } = request.params.arguments;
    try {
      const payload = {
        jobs: jobs.map(j => ({
          title: j.job_title,
          company: j.company_name,
          location: j.location || "Unknown",
          applyUrl: j.job_url,
          sourceUrl: j.job_url,
          remote: false
        }))
      };

      const response = await fetch("http://127.0.0.1:8080/admin/jobs/ingest", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        const text = await response.text();
        throw new Error(`API Error ${response.status}: ${text}`);
      }

      const responseData = await response.json();
      return {
        content: [{ type: "text", text: `Successfully sent jobs to backend API. Response: ${JSON.stringify(responseData)}` }]
      };
    } catch (e) {
      return { content: [{ type: "text", text: `Error saving jobs via API: ${e.message}` }] };
    }
  }

  throw new Error(`Unknown tool: ${request.params.name}`);
});

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((error) => {
  process.exit(1);
});
