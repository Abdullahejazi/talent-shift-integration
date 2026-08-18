import urllib.request
import urllib.parse
import json
import re
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

headers = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9'
}

queries = [
    'site:linkedin.com/in/ "Saudi Aramco" "Software Engineer"',
    'site:linkedin.com/in/ "STC" "Solutions Architect" Riyadh',
    'site:linkedin.com/in/ "Elm" "Software Engineer" Riyadh',
    'site:linkedin.com/in/ "SDAIA" "AI Engineer" Riyadh',
    'site:linkedin.com/in/ "NEOM" "Engineer" Saudi Arabia',
    'site:linkedin.com/in/ "Al Rajhi Bank" "DevOps" Riyadh',
    'site:linkedin.com/in/ "Tamara" "Product Manager" Riyadh',
    'site:linkedin.com/in/ "Jahez" "Backend Engineer" Riyadh',
    'site:linkedin.com/in/ "SNB" "Cybersecurity" Riyadh',
    'site:linkedin.com/in/ "Lucid Motors" "Software" Saudi Arabia'
]

found_profiles = []

for q in queries:
    url = f"https://html.duckduckgo.com/html/?q={urllib.parse.quote(q)}"
    req = urllib.request.Request(url, headers=headers)
    try:
        resp = urllib.request.urlopen(req, timeout=8, context=ctx)
        html = resp.read().decode('utf-8', errors='ignore')
        
        # Extract linkedin profile links
        # Match pattern: uddg=...linkedin.com/in/... or direct href
        links = re.findall(r'linkedin\.com/in/([a-zA-Z0-9_-]+)', html)
        for handle in links:
            if handle not in ['search', 'feed', 'jobs', 'company', 'pub', '404']:
                if handle not in found_profiles:
                    found_profiles.append(handle)
                    print(f"Found real profile: {handle}")
    except Exception as e:
        print(f"Query '{q}' failed: {e}")

print(f"\nTotal real indexed LinkedIn profiles harvested: {len(found_profiles)}")
with open("scripts/harvested_real_linkedin_slugs.json", "w") as f:
    json.dump(found_profiles, f, indent=2)
