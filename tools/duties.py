"""Pharmacies on duty (εφημερεύοντα) for all of Greece, into one small file for the app.

Sources, both public pages:
  - Attica: fsa-efimeries.gr, the Pharmacists' Association of Attica's own duty site (today and tomorrow).
  - Everywhere else (and Piraeus, which has its own association): vrisko.gr's duty pages, which the
    local pharmacists' associations send their lists to. One page per area; areas listed per prefecture.

Output out/duties.json:
  {"updated": epoch, "d": [{"n": name, "a": address, "r": area, "t": phone, "la": lat, "lo": lon,
                            "s": [[start, end], ...], "src": "fsa" | "vrisko"}, ...]}
Times are epoch seconds. Shifts that already ended are dropped; shifts from the previous file that
haven't ended yet are kept (so tonight's overnight duty survives a run after midnight).
"""
import html, json, os, re, sys, time, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

ATH = ZoneInfo("Europe/Athens")
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36",
      "Accept": "text/html,*/*", "Accept-Language": "el-GR,el;q=0.9"}
OUT = "out"
PER_RUN = 110
PIRAEUS = ("peiraias", "pireas", "nikaia", "korydallos", "keratsini", "perama", "drapetsona", "salamina",
           "agios-ioannis-renti", "renti")

def fetch(url, data=None, extra=None, tries=2):
    h = dict(UA)
    if extra: h.update(extra)
    if data is not None: h["Content-Type"] = "application/x-www-form-urlencoded"
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h), timeout=40) as r:
                return r.read().decode("utf-8", "ignore")
        except Exception as e:
            err = e
            time.sleep(2)
    print("  failed", url, err)
    return None

def text(x): return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", x or ""))).strip()

def at(day, h, m=0):
    return int((datetime(day.year, day.month, day.day, tzinfo=ATH) + timedelta(hours=h, minutes=m)).timestamp())

# ---- Attica: "8 ΠΡΩΙ - 2 ΜΕΣΗΜΕΡΙ & 5 ΑΠΟΓΕΥΜΑ - 8 ΠΡΩΙ ΕΠΟΜΕΝΗΣ" ----
WORD = r"(ΠΡΩΙ|ΠΡΩΊ|ΜΕΣΗΜΕΡΙ|ΜΕΣΗΜΕΡΙΟΥ|ΑΠΟΓΕΥΜΑ|ΑΠΟΓΕΥΜΑΤΟΣ|ΒΡΑΔΥ|ΒΡΑΔΙ|ΜΕΣΑΝΥΧΤΑ|ΜΕΣΑΝΥΧΤΑΣ)?"
def fsa_hour(num, word):
    h, m = (re.split(r"[:.]", num) + ["0"])[:2]
    h, m = int(h), int(m or 0)
    w = (word or "").replace("Ί", "Ι")
    if w.startswith("ΜΕΣΑΝΥΧ"): return 24, 0
    if w.startswith(("ΑΠΟΓΕΥΜ", "ΒΡΑΔ")) and h < 12: h += 12
    if w.startswith("ΜΕΣΗΜΕΡ") and h < 6: h += 12
    if w.startswith("ΒΡΑΔ") and h == 12: h = 24
    return h, m

def fsa_shifts(hours, day):
    out = []
    for part in hours.upper().split("&"):
        m = re.search(r"(\d{1,2}(?:[:.]\d{2})?)\s*" + WORD + r"\s*-\s*(\d{1,2}(?:[:.]\d{2})?)\s*" + WORD, part)
        if not m: continue
        h1, m1 = fsa_hour(m.group(1), m.group(2)); h2, m2 = fsa_hour(m.group(3), m.group(4))
        a, b = at(day, h1, m1), at(day, h2, m2)
        if "ΕΠΟΜΕΝ" in part or b <= a: b = at(day + timedelta(days=1), h2, m2)
        out.append([a, b])
    return out

def attica(day):
    s = fetch("https://fsa-efimeries.gr/Home/FilteredHomeResults",
              data=f"Date={day.isoformat()}&IsOpen=false".encode(), extra={"HX-Request": "true"})
    if not s: return []
    res = []
    for m in re.finditer(r'<div id="(\d+)" class="card w-100">(.*?)card-footer(.*?)</div>\s*</div>\s*</div>', s, re.S):
        _id, body, foot = m.groups()
        spans = [text(x) for x in re.findall(r"<span>(.*?)</span>", body, re.S)]
        ll = re.search(r"query=([0-9.]+)%2C([0-9.]+)", foot)
        if not ll or len(spans) < 2: continue
        sh = fsa_shifts(spans[1], day)
        if not sh: print("  hours?", spans[1]); continue
        phone = re.search(r'href="tel:([0-9 ]+)"', foot)
        res.append({"n": spans[0], "a": text(re.search(r'<h6 class="">(.*?)</h6>', body, re.S).group(1)),
                    "r": text(re.search(r"card-subtitle[^>]*>(.*?)</div>", body, re.S).group(1)),
                    "t": phone.group(1).replace(" ", "") if phone else "",
                    "la": round(float(ll.group(1)), 6), "lo": round(float(ll.group(2)), 6), "s": sh, "src": "fsa"})
    return res

# ---- vrisko.gr: "Παρασκευή 17:00- 08:00" ----
DAYS = ["Δευτέρα", "Τρίτη", "Τετάρτη", "Πέμπτη", "Παρασκευή", "Σάββατο", "Κυριακή"]
BLOCKED = {"n": 0}
def vrisko_area(slug, prefecture):
    """None when turned away (too many requests)."""
    s = fetch(f"https://www.vrisko.gr/efimeries-farmakeion/{slug}/")
    if not s or "dutiesfilterddl" not in s:
        BLOCKED["n"] += 1
        print("  no list:", slug, len(s or ""), re.sub(r"\s+", " ", (s or "")[:160]))
        return None
    s = html.unescape(s)
    dm = re.search(r'data-selector="Today">\s*<span>(\d+)/(\d+)/(\d{4})', s)
    if not dm: return []
    today = datetime(int(dm.group(3)), int(dm.group(2)), int(dm.group(1))).date()
    res = []
    for b in re.split(r'<div class="duty-container" ', s)[1:]:
        if "Εφημερεύον" not in b: continue          # on duty only (not just "extended hours")
        did = re.match(r'data-id="(\d+)"', b)
        ll = re.search(r"daddr=([0-9.]+),([0-9.]+)", b)
        if not ll or float(ll.group(1)) < 34 or float(ll.group(2)) < 19: continue   # no position (0,0)
        hours = re.search(r'duty-container-hours-text">(.*?)</div>', b, re.S)
        hours = text(hours.group(1)) if hours else ""
        day, sh = today, []
        for m in re.finditer(r"(?:(" + "|".join(DAYS) + r")\s*)?(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})", hours):
            if m.group(1):
                wd = DAYS.index(m.group(1))
                day = today + timedelta(days=(wd - today.weekday()) % 7)
            a, b2 = at(day, int(m.group(2)), int(m.group(3))), at(day, int(m.group(4)), int(m.group(5)))
            if m.group(4, 5) == ("23", "59"): b2 += 60
            if b2 <= a: b2 = at(day + timedelta(days=1), int(m.group(4)), int(m.group(5)))
            sh.append([a, b2])
        if not sh: print("  hours?", slug, hours); continue
        name = re.search(r"<strong><span>(.*?)</span></strong>", b, re.S)
        addr = re.search(r'duty-container-text">(.*?)</span>', b, re.S)
        tel = re.search(r'data-tel="([0-9 ]+)"', b)
        a = text(addr.group(1)) if addr else ""
        res.append({"id": "v" + (did.group(1) if did else a), "n": text(name.group(1)) if name else "",
                    "a": a.split(",")[0].strip(), "r": a.rsplit(",", 1)[1].strip() if "," in a else prefecture.title(),
                    "t": tel.group(1).replace(" ", "") if tel else "",
                    "la": round(float(ll.group(1)), 6), "lo": round(float(ll.group(2)), 6), "s": sh, "src": "vrisko"})
    return res

def vrisko_areas():
    """All areas, kept for a day (listing them is 43 requests)."""
    path = os.path.join(OUT, "areas.json")
    try:
        c = json.load(open(path))
        if time.time() - c["at"] < 86400 and len(c["areas"]) > 100: return [tuple(x) for x in c["areas"]]
    except Exception:
        pass
    areas = list_areas()
    if len(areas) > 100: json.dump({"at": int(time.time()), "areas": areas}, open(path, "w"), ensure_ascii=False)
    return areas

def list_areas():
    s = fetch("https://www.vrisko.gr/efimeries-farmakeion/")
    if not s: return []
    s = html.unescape(s)
    pref = re.findall(r'SelectedPrefecture=([^"&]+)"', s)
    single = re.findall(r'href="(?:https://www\.vrisko\.gr)?/efimeries-farmakeion/([a-z0-9-]+)/?"', s)
    areas = [(a, "") for a in single]
    for p in dict.fromkeys(pref):
        name = urllib.parse.unquote(p)
        page = fetch("https://www.vrisko.gr/efimeries-farmakeion?SelectedPrefecture=" + p)
        time.sleep(0.4)
        if not page: continue
        slugs = sorted(set(re.findall(r'/efimeries-farmakeion/([a-z0-9-]+)/?"', html.unescape(page))))
        if name == "ΑΤΤΙΚΗΣ":   # Attica comes from the association itself; vrisko only for Piraeus' association
            slugs = [x for x in slugs if any(k in x for k in PIRAEUS)]
            print("  Piraeus areas:", slugs)
        areas += [(x, name) for x in slugs]
    seen, out = set(), []
    for a, p in areas:
        if a not in seen: seen.add(a); out.append((a, p))
    return out

def main():
    os.makedirs(OUT, exist_ok=True)
    now = int(time.time())
    today = datetime.now(ATH).date()
    duties = []
    for day in (today, today + timedelta(days=1)):
        got = attica(day); print(f"Attica {day}: {len(got)}"); duties += got
    areas = vrisko_areas(); print("vrisko areas:", len(areas))
    # a slice of the areas each run (vrisko turns us away after ~150 pages), going round: with a run
    # every hour each area is read every 3-4 hours, and a day's list (with tonight's duty) is known by morning
    st_path = os.path.join(OUT, "state.json")
    try: cursor = json.load(open(st_path))["cursor"]
    except Exception: cursor = 0
    n0 = len(duties); done = 0; refused = 0
    while done < min(PER_RUN, len(areas)) and refused < 2:
        slug, pref = areas[(cursor + done) % len(areas)]
        got = vrisko_area(slug, pref)
        if got is None: refused += 1; time.sleep(20); continue
        refused = 0; duties += got; done += 1
        time.sleep(1.2)
        if done % 25 == 0: print(f"  {done} areas, {len(duties) - n0} duties")
    cursor = (cursor + done) % max(1, len(areas))
    json.dump({"cursor": cursor}, open(st_path, "w"))
    print(f"vrisko: read {done} areas (next starts at {cursor})")
    print("vrisko duties:", len(duties) - n0, "· turned away", BLOCKED["n"], "times")
    # keep shifts from the last file that haven't ended (overnight duty after midnight)
    try:
        old = json.load(open(os.path.join(OUT, "duties.json")))["d"]
    except Exception:
        old = []
    merged = {}
    for d in old + duties:
        if d["la"] < 34 or d["lo"] < 19: continue
        key = (round(d["la"], 4), round(d["lo"], 4))
        cur = merged.setdefault(key, {k: v for k, v in d.items() if k != "s"} | {"s": []})
        if d in duties: cur.update({k: v for k, v in d.items() if k != "s"})
        for s in d["s"]:
            if s[1] > now and s not in cur["s"]: cur["s"].append(s)
    out = []
    for d in merged.values():
        d["s"].sort()
        d.pop("id", None)
        if d["s"]: out.append(d)
    out.sort(key=lambda d: (d["la"], d["lo"]))
    if len(out) < 30:
        print("Too few duties, keeping the old file"); sys.exit(1)
    json.dump({"updated": now, "d": out}, open(os.path.join(OUT, "duties.json"), "w"), ensure_ascii=False, separators=(",", ":"))
    print("written", len(out), os.path.getsize(os.path.join(OUT, "duties.json")), "bytes")

if __name__ == "__main__":
    main()
