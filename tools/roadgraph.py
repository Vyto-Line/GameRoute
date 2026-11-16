"""
Builds GameRoute's offline road file for Greece (roads-gr.bin) from OpenStreetMap (Geofabrik).

The phone routes on it with no signal (OfflineRouter.kt). What it holds:
  - the junctions (vertices) and the roads between them (edges), with their shape,
    length, driving speed, speed limit, one-way, roundabout / toll / ramp flags, class and name;
  - turn restrictions (no left turn, only straight on ...), so offline routes don't make
    illegal turns;
  - a grid index (0.01°) to find the road nearest to a point.

Layout (little-endian, every section 4-byte aligned), version 1:
  'GRRG' i32 version, i32 N (vertices), i32 E (edges), i32 G (shape points), i32 R (restrictions),
  i32 nameCount, i32 nameBytes, f64 gridLat0, f64 gridLon0, f64 cell, i32 rows, i32 cols, i32 gridEntries
  vLat i32[N], vLon i32[N]                       (1e-6 degrees)
  eA i32[E], eB i32[E], eLen i32[E] (dm), eName i32[E], eGeo i32[E+1] (shape points CSR)
  eSpeed u8[E] (km/h driven), eMax u8[E] (limit km/h, 0 = unknown), eFlags u8[E], eCls u8[E]
  gLat i32[G], gLon i32[G]                       (points between the two ends, a → b)
  adjOff i32[N+1], adj i32[2E]                   (edges touching each vertex)
  rFrom i32[R], rVia i32[R], rTo i32[R], rKind u8[R] (0 no_*, 1 only_*)
  nameOff i32[nameCount+1], nameBytes u8[nameBytes] (UTF-8)
  cellOff i32[rows*cols+1], cellEdges i32[gridEntries]

eFlags: 1 forward allowed (a → b), 2 backward allowed, 4 roundabout, 8 toll, 16 ramp (_link),
        32 motorway or trunk.
eCls: 0 motorway, 1 trunk, 2 primary, 3 secondary, 4 tertiary, 5 unclassified, 6 residential,
      7 living street, 8 service.
"""
import math
import struct
import sys
import time

import numpy as np
import osmium

SRC = sys.argv[1] if len(sys.argv) > 1 else "greece-latest.osm.pbf"
OUT = sys.argv[2] if len(sys.argv) > 2 else "roads-gr.bin"

CLS = {
    "motorway": (0, 110), "motorway_link": (0, 60),
    "trunk": (1, 90), "trunk_link": (1, 50),
    "primary": (2, 65), "primary_link": (2, 40),
    "secondary": (3, 55), "secondary_link": (3, 35),
    "tertiary": (4, 45), "tertiary_link": (4, 30),
    "unclassified": (5, 40), "road": (5, 30),
    "residential": (6, 30), "living_street": (7, 12),
    "service": (8, 18),
}
NO = {"no", "private", "agricultural", "forestry", "delivery", "emergency"}
YES = {"yes", "designated", "permissive", "destination"}


def kmh(v):
    if not v:
        return 0
    v = v.strip().lower()
    try:
        if v.endswith("mph"):
            return int(float(v[:-3].strip()) * 1.609)
        return int(float(v.split(";")[0].split()[0]))
    except Exception:
        return {"gr:urban": 50, "gr:rural": 90, "gr:motorway": 130, "gr:trunk": 110, "walk": 8, "none": 130}.get(v, 0)


# ---------------------------------------------------------------- 1) turn restrictions
class Rel(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.rs = []          # (fromWay, viaNode, toWay, kind)
        self.ways = set()

    def relation(self, r):
        t = r.tags
        if t.get("type") != "restriction":
            return
        kind = t.get("restriction") or t.get("restriction:motorcar") or t.get("restriction:motor_vehicle")
        if not kind or "except" in t and ("motorcar" in t.get("except", "")):
            return
        if kind.startswith("no_"):
            k = 0
        elif kind.startswith("only_"):
            k = 1
        else:
            return
        fw = tw = vn = None
        for m in r.members:
            if m.role == "from" and m.type == "w":
                fw = m.ref
            elif m.role == "to" and m.type == "w":
                tw = m.ref
            elif m.role == "via" and m.type == "n":
                vn = m.ref
            elif m.role == "via" and m.type == "w":
                return  # via-way restrictions: rare, left out
        if fw and tw and vn:
            self.rs.append((fw, vn, tw, k))
            self.ways.add(fw)
            self.ways.add(tw)


# ---------------------------------------------------------------- 2) roads
class Ways(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.ids = []       # way id per road
        self.start = [0]    # node offsets per road
        self.nodes = []     # node ids, all roads after each other
        self.lat = []       # their coordinates (1e-6)
        self.lon = []
        self.attr = []      # (cls, speed, maxspeed, flags, name)

    def way(self, w):
        t = w.tags
        hw = t.get("highway")
        if hw not in CLS:
            return
        if t.get("area") == "yes":
            return
        acc = t.get("motor_vehicle") or t.get("motorcar") or t.get("vehicle") or t.get("access")
        if acc in NO and not (t.get("motorcar") in YES or t.get("motor_vehicle") in YES):
            return
        if hw == "service" and t.get("service") in ("emergency_access",):
            return
        cls, speed = CLS[hw]
        mx = kmh(t.get("maxspeed"))
        if mx:
            speed = min(speed * 1.25, mx * 0.9) if cls >= 2 else min(mx * 0.92, 130)
        if hw == "service" and t.get("service") in ("parking_aisle", "driveway", "drive-through"):
            speed = 10
        flags = 0
        ow = t.get("oneway")
        rb = t.get("junction") in ("roundabout", "circular")
        if ow in ("yes", "true", "1") or rb or (hw in ("motorway", "motorway_link") and ow != "no"):
            flags |= 1
        elif ow == "-1":
            flags |= 2
        else:
            flags |= 3
        if rb:
            flags |= 4
        if t.get("toll") == "yes":
            flags |= 8
        if hw.endswith("_link"):
            flags |= 16
        if cls <= 1:
            flags |= 32
        name = t.get("name") or ""
        ref = t.get("ref") or ""
        label = f"{name} ({ref})" if name and ref else (name or ref)
        try:
            pts = [(n.ref, n.location.lat, n.location.lon) for n in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(pts) < 2:
            return
        self.ids.append(w.id)
        for ref_, la, lo in pts:
            self.nodes.append(ref_)
            self.lat.append(int(round(la * 1e6)))
            self.lon.append(int(round(lo * 1e6)))
        self.start.append(len(self.nodes))
        self.attr.append((cls, int(round(speed)), min(mx, 255), flags, label))


def hav(la1, lo1, la2, lo2):
    p1 = math.radians(la1 / 1e6); p2 = math.radians(la2 / 1e6)
    dp = p2 - p1; dl = math.radians((lo2 - lo1) / 1e6)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(a))


def main():
    t0 = time.time()
    rel = Rel()
    rel.apply_file(SRC)
    print(f"restrictions: {len(rel.rs)} ({time.time() - t0:.0f} s)", flush=True)

    ways = Ways()
    ways.apply_file(SRC, locations=True, idx="flex_mem")
    nodes = np.array(ways.nodes, dtype=np.int64)
    lat = np.array(ways.lat, dtype=np.int32)
    lon = np.array(ways.lon, dtype=np.int32)
    start = np.array(ways.start, dtype=np.int64)
    print(f"roads: {len(ways.ids)}, points: {len(nodes)} ({time.time() - t0:.0f} s)", flush=True)
    del ways.nodes, ways.lat, ways.lon

    # junctions: a node on more than one road, or the end of a road
    uniq, counts = np.unique(nodes, return_counts=True)
    ends = np.unique(np.concatenate([nodes[start[:-1]], nodes[start[1:] - 1]]))
    junc = np.union1d(uniq[counts >= 2], ends)
    is_j = np.isin(nodes, junc)
    print(f"junctions: {len(junc)} ({time.time() - t0:.0f} s)", flush=True)

    # plain lists: much faster than numpy element by element in the loop below
    nodes_l = nodes.tolist(); lat = lat.tolist(); lon = lon.tolist(); is_j = is_j.tolist(); start = start.tolist()
    vid = {}           # node id → vertex index (junctions only)
    vlat = []; vlon = []
    eA = []; eB = []; eLen = []; eName = []; eGeo = [0]; eSpeed = []; eMax = []; eFlags = []; eCls = []
    gLat = []; gLon = []
    names = {"": 0}; nameList = [""]
    want = rel.ways
    wayEdges = {}      # way id → [(edge, vertexA, vertexB)] for restriction ways

    def vertex(i):
        n = nodes_l[i]
        v = vid.get(n)
        if v is None:
            v = len(vlat); vid[n] = v; vlat.append(int(lat[i])); vlon.append(int(lon[i]))
        return v

    for k, wid in enumerate(ways.ids):
        s, e = int(start[k]), int(start[k + 1])
        cls, speed, mx, flags, label = ways.attr[k]
        nid = names.get(label)
        if nid is None:
            nid = len(nameList); names[label] = nid; nameList.append(label)
        a = s
        length = 0.0
        for i in range(s + 1, e):
            length += hav(lat[i - 1], lon[i - 1], lat[i], lon[i])
            if is_j[i] or i == e - 1:
                va = vertex(a); vb = vertex(i)
                if va != vb or i - a > 1:
                    ei = len(eA)
                    eA.append(va); eB.append(vb); eLen.append(max(1, int(round(length * 10))))
                    eName.append(nid); eSpeed.append(max(5, min(speed, 140))); eMax.append(mx); eFlags.append(flags); eCls.append(cls)
                    for j in range(a + 1, i):
                        gLat.append(int(lat[j])); gLon.append(int(lon[j]))
                    eGeo.append(len(gLat))
                    if wid in want:
                        wayEdges.setdefault(wid, []).append((ei, va, vb))
                a = i
                length = 0.0
    N = len(vlat); E = len(eA); G = len(gLat)
    print(f"vertices {N}, edges {E}, shape points {G} ({time.time() - t0:.0f} s)", flush=True)

    # restrictions → (from edge, via vertex, to edge)
    rF = []; rV = []; rT = []; rK = []
    for fw, vn, tw, kind in rel.rs:
        v = vid.get(vn)
        if v is None:
            continue
        fe = [ei for ei, a, b in wayEdges.get(fw, []) if a == v or b == v]
        te = [ei for ei, a, b in wayEdges.get(tw, []) if a == v or b == v]
        if len(fe) != 1 or len(te) != 1:
            continue
        rF.append(fe[0]); rV.append(v); rT.append(te[0]); rK.append(kind)
    R = len(rF)
    print(f"restrictions kept: {R}", flush=True)

    eA_np = np.array(eA, dtype=np.int32); eB_np = np.array(eB, dtype=np.int32)
    # adjacency (CSR)
    ends2 = np.concatenate([eA_np, eB_np])
    eids = np.concatenate([np.arange(E, dtype=np.int32), np.arange(E, dtype=np.int32)])
    order = np.argsort(ends2, kind="stable")
    adj = eids[order]
    adjOff = np.zeros(N + 1, dtype=np.int64)
    np.add.at(adjOff, ends2 + 1, 1)
    adjOff = np.cumsum(adjOff).astype(np.int32)

    # grid of edges (0.01°), by each edge's box
    vla = np.array(vlat, dtype=np.int64); vlo = np.array(vlon, dtype=np.int64)
    geo = np.array(eGeo, dtype=np.int64)
    gla = np.array(gLat, dtype=np.int64); glo = np.array(gLon, dtype=np.int64)
    lat0 = (vla.min() / 1e6) - 0.01; lon0 = (vlo.min() / 1e6) - 0.01
    cell = 0.01
    rows = int(((vla.max() / 1e6) - lat0) / cell) + 2
    cols = int(((vlo.max() / 1e6) - lon0) / cell) + 2
    pairs_c = []; pairs_e = []
    for ei in range(E):
        las = [vla[eA[ei]], vla[eB[ei]]]; los = [vlo[eA[ei]], vlo[eB[ei]]]
        g0, g1 = geo[ei], geo[ei + 1]
        if g1 > g0:
            las.extend(gla[g0:g1].tolist()); los.extend(glo[g0:g1].tolist())
        r0 = int((min(las) / 1e6 - lat0) / cell); r1 = int((max(las) / 1e6 - lat0) / cell)
        c0 = int((min(los) / 1e6 - lon0) / cell); c1 = int((max(los) / 1e6 - lon0) / cell)
        for r in range(r0, r1 + 1):
            for c in range(c0, c1 + 1):
                pairs_c.append(r * cols + c); pairs_e.append(ei)
    pc = np.array(pairs_c, dtype=np.int64); pe = np.array(pairs_e, dtype=np.int32)
    o = np.argsort(pc, kind="stable")
    cellEdges = pe[o]
    cellOff = np.zeros(rows * cols + 1, dtype=np.int64)
    np.add.at(cellOff, pc + 1, 1)
    cellOff = np.cumsum(cellOff).astype(np.int32)
    print(f"grid {rows}x{cols}, entries {len(cellEdges)} ({time.time() - t0:.0f} s)", flush=True)

    nb = [s.encode("utf-8") for s in nameList]
    nameOff = np.zeros(len(nb) + 1, dtype=np.int32)
    nameOff[1:] = np.cumsum([len(b) for b in nb])
    nameBytes = b"".join(nb)

    def pad(b):
        return b + b"\0" * ((-len(b)) % 4)

    with open(OUT, "wb") as f:
        f.write(b"GRRG")
        f.write(struct.pack("<7i", 1, N, E, G, R, len(nameList), len(nameBytes)))
        f.write(struct.pack("<3d3i", lat0, lon0, cell, rows, cols, len(cellEdges)))
        i32 = lambda a: np.asarray(a, dtype="<i4").tobytes()
        u8 = lambda a: pad(np.asarray(a, dtype=np.uint8).tobytes())
        for part in (i32(vlat), i32(vlon), i32(eA), i32(eB), i32(eLen), i32(eName), i32(eGeo),
                     u8(eSpeed), u8(eMax), u8(eFlags), u8(eCls), i32(gLat), i32(gLon),
                     i32(adjOff), i32(adj), i32(rF), i32(rV), i32(rT), u8(rK),
                     i32(nameOff), pad(nameBytes), i32(cellOff), i32(cellEdges)):
            f.write(part)
    import os
    print(f"wrote {OUT}: {os.path.getsize(OUT) / 1e6:.0f} MB ({time.time() - t0:.0f} s)", flush=True)


if __name__ == "__main__":
    main()
