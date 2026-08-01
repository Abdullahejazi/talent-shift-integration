import http from 'node:http';
import dns from 'node:dns/promises';
import net from 'node:net';
import { chromium } from 'playwright';

const port = Number(process.env.BROWSER_RENDERER_PORT || 3001);
const secret = process.env.BROWSER_RENDERER_SECRET || '';
const maximumBytes = 5_000_000;
let browser;
let active = 0;

function privateAddress(address) {
  if (!net.isIP(address)) return true;
  return address === '::1' || address.startsWith('fc') || address.startsWith('fd') ||
    address.startsWith('fe80:') || address.startsWith('127.') || address.startsWith('10.') ||
    address.startsWith('192.168.') || /^172\.(1[6-9]|2\d|3[01])\./.test(address) ||
    address.startsWith('169.254.') || address === '0.0.0.0';
}

async function safeTarget(value) {
  const target = new URL(value);
  if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password) throw new Error('unsafe target');
  const addresses = await dns.lookup(target.hostname, { all: true });
  if (!addresses.length || addresses.some(item => privateAddress(item.address))) throw new Error('private target blocked');
  return target;
}

async function render(target) {
  browser ||= await chromium.launch({ headless: true });
  const context = await browser.newContext({
    userAgent: 'TalentShift/1.0 (+public-job-renderer)', acceptDownloads: false,
    javaScriptEnabled: true
  });
  const page = await context.newPage();
  await page.route('**/*', route => ['image', 'media', 'font'].includes(route.request().resourceType()) ? route.abort() : route.continue());
  try {
    await page.goto(target.toString(), { waitUntil: 'domcontentloaded', timeout: 20_000 });
    await page.waitForTimeout(1500);
    const html = await page.content();
    if (Buffer.byteLength(html) > maximumBytes) throw new Error('rendered response too large');
    return html;
  } finally { await context.close(); }
}

const server = http.createServer(async (request, response) => {
  let slot = false;
  try {
    const input = new URL(request.url, `http://${request.headers.host || 'localhost'}`);
    if (request.method !== 'GET' || input.pathname !== '/render') { response.writeHead(404).end(); return; }
    if (secret && request.headers.authorization !== `Bearer ${secret}`) { response.writeHead(401).end(); return; }
    if (active >= 2) { response.writeHead(429, { 'Retry-After': '5' }).end(); return; }
    const target = await safeTarget(input.searchParams.get('url') || '');
    active++; slot = true;
    const html = await render(target);
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(html);
  } catch (error) {
    response.writeHead(400, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ error: error.message }));
  } finally { if (slot) active = Math.max(0, active - 1); }
});

server.listen(port, '127.0.0.1', () => process.stdout.write(`TalentShift renderer listening on 127.0.0.1:${port}\n`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => { await browser?.close(); server.close(); });
