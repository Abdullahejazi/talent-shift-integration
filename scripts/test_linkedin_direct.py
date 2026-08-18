import urllib.request
import ssl
import sys

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def http_error_302(self, req, fp, code, msg, headers):
        return fp
    http_error_301 = http_error_302
    http_error_303 = http_error_302
    http_error_307 = http_error_302

def test_handle(handle):
    url = f"https://www.linkedin.com/in/{handle}"
    req = urllib.request.Request(
        url,
        headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9',
        }
    )
    try:
        opener = urllib.request.build_opener()
        resp = opener.open(req, timeout=5)
        final_url = resp.geturl()
        code = resp.getcode()
        html = resp.read(2048).decode('utf-8', errors='ignore')
        
        if '404' in final_url or 'Page Not Found' in html or "This page doesn’t exist" in html:
            return False, f"404 ({final_url})"
        return True, f"OK ({code}, {final_url})"
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False, "404 HTTP Error"
        elif e.code == 999:
            return None, "999 Rate Limit / Anti-bot (Needs browser verification)"
        return False, f"HTTP Error {e.code}"
    except Exception as ex:
        return False, f"Error: {ex}"

test_handles = [
    "faisal-alharbi",
    "deema-albishi",
    "bill-gates",
    "williamhgates",
    "satyanadella",
    "sundarpichai",
    "ahmed-alghamdi",
    "khalid-alqahtani"
]

for h in test_handles:
    ok, detail = test_handle(h)
    print(f"{h:25} -> ok={ok} | {detail}")
