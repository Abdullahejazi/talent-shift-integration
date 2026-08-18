import urllib.request
import ssl
import json
from concurrent.futures import ThreadPoolExecutor

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

def check_one_handle(handle):
    url = f"https://www.linkedin.com/in/{handle}"
    req = urllib.request.Request(
        url,
        headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9'
        }
    )
    try:
        resp = urllib.request.urlopen(req, timeout=4, context=ctx)
        final = resp.geturl()
        html = resp.read(2048).decode('utf-8', errors='ignore')
        if '404' in final or 'Page Not Found' in html or "This page doesn’t exist" in html:
            return handle, False, "404"
        return handle, True, f"200 OK ({final})"
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return handle, False, "404"
        elif e.code == 999:
            return handle, "999", "999 Anti-bot"
        return handle, False, f"HTTP_{e.code}"
    except Exception as e:
        return handle, False, str(e)

# Broad list of potential Saudi tech handles
test_handles = [
    "faisal-alharbi", "williamhgates", "satyanadella", "sundarpichai",
    "ahmed-alghamdi", "mohammed-alshehri", "abdullah-alotaibi", "khalid-alzahrani",
    "omar-aldossari", "saad-alshammari", "fahad-almutairi", "tariq-alamri",
    "majed-alqahtani", "nasser-alsubaie", "mansour-alenazi", "sultan-albishi",
    "hassan-alrashidi", "abdulaziz-altamimi", "waleed-aljuhani", "saud-alkhatib",
    "hamad-almalki", "yazeed-alqarni", "rayan-alhusseini", "saleh-alsaad",
    "badr-alnuaimi", "talal-alajmi", "ibrahim-alsalem", "ziyad-alharbi",
    "alwaleed-talal", "alwaleed", "faisal-harbi", "ahmed-ghamdi",
    "sarah-alghamdi", "abdullah-alshehri", "mohammed-alotaibi", "khalid-alqahtani",
    "noura-alzahrani", "turki-aldossari", "reem-alshammari", "layla-alsubaie",
    "rawan-alkhatib", "maha-alqarni", "nouf-alsaad", "arwa-alajmi",
    "dalal-alsayed", "shatha-altuwaijri", "faris-alghamdi", "lina-alshehri",
    "hassan-alotaibi", "basma-alqahtani", "badr-alzahrani", "amal-aldossari",
    "jude-almutairi", "areej-alharthi"
]

print(f"Testing {len(test_handles)} handles with ThreadPoolExecutor...")
confirmed_200 = []
rejected_404 = []
anti_bot_999 = []

with ThreadPoolExecutor(max_workers=20) as executor:
    results = list(executor.map(check_one_handle, test_handles))

for handle, status, msg in results:
    if status is True:
        print(f"[OK 200]  {handle:25} -> {msg}")
        confirmed_200.append(handle)
    elif status == "999":
        anti_bot_999.append(handle)
    else:
        print(f"[ERR 404] {handle:25} -> {msg}")
        rejected_404.append(handle)

print("\n--- RESULTS ---")
print(f"Confirmed 200: {len(confirmed_200)} -> {confirmed_200}")
print(f"404 Rejected:  {len(rejected_404)} -> {rejected_404}")
print(f"999 Anti-bot:  {len(anti_bot_999)}")
