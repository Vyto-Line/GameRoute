"""Probe: fetch the pharmacy duty sites and save the raw pages, to learn their formats."""
import os, urllib.request, urllib.parse, ssl, json, sys
os.makedirs("probe", exist_ok=True)
H = {"User-Agent": "GameRoute duty list (github.com/Vyto-Line/GameRoute)", "Accept-Language": "el-GR,el;q=0.9"}
def get(name, url, data=None, extra=None):
    try:
        req = urllib.request.Request(url, data=data, headers={**H, **(extra or {})})
        with urllib.request.urlopen(req, timeout=25) as r:
            b = r.read()
            open(f"probe/{name}", "wb").write(b)
            print(name, r.status, len(b), r.headers.get("content-type"))
    except Exception as e:
        print(name, "ERROR", e)
urls = json.loads(sys.argv[1]) if len(sys.argv) > 1 else None
for n, u in (urls or [
    ("fsa_home.html", "https://fsa-efimeries.gr/"),
    ("fsa_filtered_get.html", "https://fsa-efimeries.gr/Home/FilteredHomeResults"),
    ("efh_root.html", "https://efhmeries.gr/"),
    ("efh_herakleion.html", "https://herakleion.efhmeries.gr/"),
    ("efh_larisa.html", "https://larisa.efhmeries.gr/"),
    ("vrisko_index.html", "https://www.vrisko.gr/efimeries-farmakeion/"),
    ("vrisko_thess.html", "https://www.vrisko.gr/efimeries-farmakeion/thessaloniki/"),
    ("vrisko_patra.html", "https://www.vrisko.gr/efimeries-farmakeion/patra/"),
    ("fsth.html", "https://www.fsth.gr/"),
    ("pst.html", "https://www.pst.gr/"),
    ("syfa.html", "https://www.syfa.gr/"),
]):
    get(n, u)
get("fsa_filtered_post.html", "https://fsa-efimeries.gr/Home/FilteredHomeResults", data=b"", extra={"HX-Request": "true"})
