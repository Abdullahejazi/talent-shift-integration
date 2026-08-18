import urllib.request
import ssl
import json
import time

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

# Candidates list to test
candidates_to_test = [
    # Prominent tech leaders, engineers, and professionals in KSA
    "faisal-alharbi",
    "williamhgates",
    "satyanadella",
    "alwaleed-talal",
    "alwaleed",
    "faisal-harbi",
    "ziyad-alharbi",
    "ziyadalharbi",
    "ahmed-alghamdi",
    "ahmedalghamdi",
    "mohammed-alshehri",
    "mohammedalshehri",
    "abdullah-alotaibi",
    "abdullahalotaibi",
    "khalid-alzahrani",
    "khalidalzahrani",
    "omar-aldossari",
    "omaraldossari",
    "saad-alshammari",
    "saadalshammari",
    "fahad-almutairi",
    "fahadalmutairi",
    "tariq-alamri",
    "tariqalamri",
    "majed-alqahtani",
    "majedalqahtani",
    "nasser-alsubaie",
    "nasseralsubaie",
    "mansour-alenazi",
    "mansouralenazi",
    "sultan-albishi",
    "sultanalbishi",
    "hassan-alrashidi",
    "hassanalrashidi",
    "abdulaziz-altamimi",
    "abdulazizaltamimi",
    "waleed-aljuhani",
    "waleedaljuhani",
    "saud-alkhatib",
    "saudalkhatib",
    "hamad-almalki",
    "hamadalmalki",
    "yazeed-alqarni",
    "yazeedalqarni",
    "rayan-alhusseini",
    "rayanalhusseini",
    "saleh-alsaad",
    "salehalsaad",
    "badr-alnuaimi",
    "badralnuaimi",
    "talal-alajmi",
    "talalalajmi",
    "ibrahim-alsalem",
    "ibrahimalsalem",
    "faisal-al-harbi",
    "sarah-al-ghamdi",
    "abdullah-al-shehri",
    "mohammed-al-otaibi",
    "khalid-al-qahtani",
    "noura-al-zahrani",
    "turki-al-dossari",
    "reem-al-shammari",
    "fahad-al-mutairi",
    "omar-al-amri",
    "saad-al-harthi",
    "layla-al-subaie",
    "tariq-al-enazi"
]

def check_url(handle):
    url = f"https://www.linkedin.com/in/{handle}"
    req = urllib.request.Request(
        url,
        headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9,ar;q=0.8'
        }
    )
    try:
        resp = urllib.request.urlopen(req, timeout=5, context=ctx)
        final = resp.geturl()
        code = resp.getcode()
        html = resp.read().decode('utf-8', errors='ignore')
        if '404' in final or 'Page Not Found' in html or "This page doesn’t exist" in html:
            return False, "404"
        return True, f"200 OK ({final})"
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False, "404"
        elif e.code == 999:
            # Let's check if the URL redirects on public proxy or if it has public footprint
            return "999", "999 Anti-bot"
        return False, f"HTTP_{e.code}"
    except Exception as e:
        return False, str(e)

print(f"Testing {len(candidates_to_test)} handles...")
valid_200 = []
code_999 = []
code_404 = []

for h in candidates_to_test:
    res, detail = check_url(h)
    if res is True:
        print(f"[200 CONFIRMED] {h:25} -> {detail}")
        valid_200.append(h)
    elif res == "999":
        code_999.append(h)
    else:
        print(f"[REJECTED 404]  {h:25} -> {detail}")
        code_404.append(h)

print(f"\n--- Summary ---")
print(f"200 OK Direct: {len(valid_200)} -> {valid_200}")
print(f"999 Anti-bot:  {len(code_999)}")
print(f"404 Rejected:  {len(code_404)}")
