import urllib.request
import ssl
import time
import json

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

candidates = [
    ("Faisal Al-Harbi", "faisal-alharbi", "Saudi Aramco", "Senior Cloud Solutions Architect", "Cloud & DevOps"),
    ("Ziyad Al-Harbi", "ziyad-alharbi", "NEOM", "Lead Cybersecurity Architect", "Cybersecurity"),
    ("Ahmed Al-Ghamdi", "ahmedalghamdi", "STC", "Principal Systems Engineer", "Software Engineering"),
    ("Abdullah Al-Otaibi", "abdullahalotaibi", "Elm", "Lead Software Architect", "Software Engineering"),
    ("Khalid Al-Zahrani", "khalidalzahrani", "Al Rajhi Bank", "Senior DevOps & SRE Lead", "Cloud & DevOps"),
    ("Nasser Al-Subaie", "nasser-alsubaie", "Riyad Bank", "Senior Frontend Engineer", "Software Engineering"),
    ("Mohammed Al-Shehri", "mohammed-shehri", "SDAIA", "Director of AI Systems", "AI & Data"),
    ("Omar Al-Dossari", "omardossari", "Jahez", "Senior Backend Engineer", "Software Engineering"),
    ("Saad Al-Shammari", "saad-shammari", "Tamara", "Fintech Platform Architect", "Software Engineering"),
    ("Fahad Al-Mutairi", "fahad-mutairi", "Saudi National Bank", "Cloud Infrastructure Lead", "Cloud & DevOps"),
    ("Tariq Al-Amri", "tariq-amri", "Lucid Motors", "Senior Software Engineer", "Software Engineering"),
    ("Majed Al-Qahtani", "majed-qahtani", "Red Sea Global", "Smart Cities Lead", "Cloud & DevOps"),
    ("Mansour Al-Enazi", "mansour-enazi", "HungerStation", "Principal Product Manager", "Product & Design"),
    ("Sultan Al-Bishi", "sultan-bishi", "Floward", "Data Engineering Lead", "AI & Data"),
    ("Hassan Al-Rashidi", "hassan-rashidi", "Lean Technologies", "API Systems Architect", "Software Engineering"),
    ("Abdulaziz Al-Tamimi", "abdulaziz-tamimi", "Tabby", "Senior Mobile Engineer", "Software Engineering"),
    ("Waleed Al-Juhani", "waleed-juhani", "Sary", "B2B Tech Platform Lead", "Software Engineering"),
    ("Saud Al-Khatib", "saud-khatib", "Mrsool", "Full Stack Tech Lead", "Software Engineering"),
    ("Hamad Al-Malki", "hamad-malki", "Nana", "AI & Logistics Specialist", "AI & Data"),
    ("Yazeed Al-Qarni", "yazeed-qarni", "Foodics", "Cloud POS Systems Lead", "Cloud & DevOps"),
    ("Rayan Al-Husseini", "rayan-husseini", "Unifonic", "Telecommunications Software Architect", "Software Engineering"),
    ("Saleh Al-Saad", "saleh-saad", "STC Pay", "Lead Security Operations Engineer", "Cybersecurity"),
    ("Badr Al-Nuaimi", "badr-nuaimi", "Geidea", "Payment Core Infrastructure Lead", "Software Engineering"),
    ("Talal Al-Ajmi", "talal-ajmi", "Gathern", "Product Growth Lead", "Product & Design"),
    ("Ibrahim Al-Salem", "ibrahim-salem", "Aramco Digital", "Principal SRE Engineer", "Cloud & DevOps")
]

verified_pool = []

for name, handle, company, title, discipline in candidates:
    url = f"https://www.linkedin.com/in/{handle}"
    req = urllib.request.Request(
        url,
        headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9'
        }
    )
    time.sleep(1.2) # Avoid rate limiting
    try:
        resp = urllib.request.urlopen(req, timeout=5, context=ctx)
        final = resp.geturl()
        html = resp.read(2048).decode('utf-8', errors='ignore')
        if '404' in final or 'Page Not Found' in html or "This page doesn’t exist" in html:
            print(f"[REJECTED 404] {handle}")
            continue
        print(f"[VERIFIED 200] {handle} -> {final}")
        verified_pool.append({
            "name": name,
            "handle": handle,
            "company": company,
            "title": title,
            "discipline": discipline,
            "url": f"https://www.linkedin.com/in/{handle}"
        })
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print(f"[REJECTED 404] {handle}")
        elif e.code == 999:
            print(f"[CHALLENGE 999] {handle} (LinkedIn Anti-bot)")
        else:
            print(f"[HTTP_{e.code}] {handle}")
    except Exception as e:
        print(f"[ERR] {handle}: {e}")

print(f"\nSuccessfully verified {len(verified_pool)} profiles.")
