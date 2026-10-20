"""Cut offline region packs from one country PBF in a single streaming pass.

usage: python tools/build_packs.py philippines-latest.osm.pbf OUTDIR [--packs tools/packs.json] [--only id,id]
                                  [--index nodes.idx] [--no-landcover] [--motorable]

Writes OUTDIR/<pack id>/ with roads.geojson, places.json, parking.json, lots.json, land.geojson, water.geojson,
green.geojson and region.json (same schema the app reads, plus "areas"). Routing graphs come from
tools/clip_pack_pbf.py + tools/build_pack_tiles.py. Node locations are kept in a disk index so a whole
country fits in little RAM. land.geojson is assembled from natural=coastline rings (sea is everything else).
Add "files" checksums to region.json before shipping a pack; the app compares the graph's byte size.
"""
import argparse, ast, datetime, hashlib, json, math, os, pathlib, sys, time
import osmium

ap = argparse.ArgumentParser()
ap.add_argument('pbf'); ap.add_argument('out')
ap.add_argument('--packs', default=str(pathlib.Path(__file__).with_name('packs.json')))
ap.add_argument('--only', default='')
ap.add_argument('--index', default='')
ap.add_argument('--no-landcover', action='store_true')
ap.add_argument('--motorable', action='store_true', help='leave footway/path/steps etc. out of roads.geojson')
args = ap.parse_args()
out = pathlib.Path(args.out); out.mkdir(parents=True, exist_ok=True)
packs = json.loads(pathlib.Path(args.packs).read_text(encoding='utf-8'))['packs']
if args.only: packs = [p for p in packs if p['id'] in args.only.split(',')]
NOT_MOTORABLE = {'footway', 'path', 'steps', 'pedestrian', 'cycleway', 'bridleway', 'corridor', 'elevator', 'platform',
                 'proposed', 'construction', 'abandoned', 'razed', 'disused', 'bus_stop', 'via_ferrata'}
WATER = lambda t: t.get('natural') == 'water' or t.get('waterway') == 'riverbank' or t.get('landuse') in ('reservoir', 'basin')
GREEN = lambda t: t.get('leisure') in ('park', 'nature_reserve', 'garden') or t.get('landuse') in ('forest', 'grass', 'meadow', 'recreation_ground', 'cemetery') or t.get('natural') in ('wood', 'scrub', 'wetland')
r6 = lambda v: round(v, 6)

class Pack:
    def __init__(self, spec):
        self.spec = spec; self.id = spec['id']; self.areas = spec['areas']; self.dir = out / self.id; self.dir.mkdir(exist_ok=True)
        self.bounds = [min(a[0] for a in self.areas), min(a[1] for a in self.areas), max(a[2] for a in self.areas), max(a[3] for a in self.areas)]
        self.files = {}; self.first = {}; self.count = {}; self.kind_bytes = {}; self.seen_places = set(); self.parking = []
        self.open('roads.geojson', '{"type":"FeatureCollection","features":[', ']}')
        self.open('places.json', '[', ']')
        self.open('lots.json', '[', ']')
        if not args.no_landcover:
            for f in ('water.geojson', 'green.geojson'): self.open(f, '{"type":"FeatureCollection","features":[', ']}')
    def open(self, name, head, tail):
        f = open(self.dir / name, 'w', encoding='utf-8', newline='\n'); f.write(head); self.files[name] = (f, tail); self.first[name] = True; self.count[name] = 0
    def write(self, name, obj):
        f = self.files[name][0]; s = json.dumps(obj, separators=(',', ':'), ensure_ascii=False)
        f.write(s if self.first[name] else ',' + s); self.first[name] = False; self.count[name] += 1; return len(s) + 1
    def close(self):
        for f, tail in self.files.values(): f.write(tail); f.close()
    def has(self, lon, lat): return any(a[0] <= lon <= a[2] and a[1] <= lat <= a[3] for a in self.areas)
    def touches(self, box):  # box = minLon,minLat,maxLon,maxLat
        return any(not (box[2] < a[0] or box[0] > a[2] or box[3] < a[1] or box[1] > a[3]) for a in self.areas)
    def place(self, name, lon, lat, poi=False):
        # one entry per name per ~2 km cell for roads (segments repeat names); per ~50 m for shops, so every branch stays
        if not any(ch.isalpha() for ch in name): return  # house or stall numbers ("5", "12-A")
        key = (name, round(lat * 2000), round(lon * 2000)) if poi else (name, round(lat * 50), round(lon * 50))
        if key in self.seen_places: return
        self.seen_places.add(key); self.write('places.json', {'name': name, 'lon': r6(lon), 'lat': r6(lat)})

# Only shops, amenities (parking too) and tourism are branches; railways, rivers and other named lines repeat per segment.
def is_poi(t): return 'highway' not in t and any(k in t for k in ('amenity', 'shop', 'tourism'))
def branch_name(t):
    """OSM names most chain branches just "Jollibee" and puts the branch in branch=: "Jollibee Vito Cruz", like Google Maps."""
    name, branch = t['name'].strip(), t.get('branch', '').strip()
    return name + ' ' + branch if branch and branch.lower() not in name.lower() else name

P = [Pack(s) for s in packs]
def packs_at(lon, lat): return [p for p in P if p.has(lon, lat)]
def packs_box(box): return [p for p in P if p.touches(box)]
# lots.json (search-only, private lots included) keeps tools/build_lots.py's record shape; load just its record().
_lots_src = pathlib.Path(__file__).with_name('build_lots.py').read_text(encoding='utf-8')
exec(compile(ast.Module([n for n in ast.parse(_lots_src).body if isinstance(n, ast.FunctionDef) and n.name == 'record'], []), 'build_lots.py', 'exec'))
def add_lot(oid, lon, lat, t):
    for p in packs_at(lon, lat): p.write('lots.json', record(oid, lon, lat, t))
def parking_record(oid, lon, lat, t):
    return {'id': oid, 'lon': r6(lon), 'lat': r6(lat), 'name': t.get('name', 'Parking'), 'source': 'OpenStreetMap',
            'motorcycle': t.get('amenity') == 'motorcycle_parking', 'access': t.get('access', 'unknown')}

coast = {}  # way id -> (first node id, last node id, [(lon,lat)...])
idx = args.index or str(out / 'nodes.idx')
fp = osmium.FileProcessor(args.pbf).with_locations('sparse_file_array,' + idx)
if not args.no_landcover: fp = fp.with_areas(osmium.filter.KeyFilter('natural', 'waterway', 'landuse', 'leisure'))
fp = fp.with_filter(osmium.filter.KeyFilter('highway', 'amenity', 'natural', 'name', 'waterway', 'landuse', 'leisure'))
started = time.time(); seen = 0
for o in fp:
    seen += 1
    if seen % 2000000 == 0: print(f'{seen:,} objects, {time.time()-started:.0f}s', flush=True)
    t = dict(o.tags)
    if o.is_node():
        if not o.location.valid(): continue
        lon, lat = o.location.lon, o.location.lat; hit = packs_at(lon, lat)
        if not hit: continue
        if t.get('amenity') in ('parking', 'motorcycle_parking'): add_lot('n%d' % o.id, lon, lat, t)
        for p in hit:
            if t.get('amenity') in ('parking', 'motorcycle_parking'): p.parking.append(parking_record(str(o.id), lon, lat, t))
            if t.get('name') and any(k in t for k in ('amenity', 'tourism', 'shop', 'place', 'public_transport')): p.place(branch_name(t), lon, lat, poi='place' not in t)
    elif o.is_way():
        try: pts = [(n.location.lon, n.location.lat) for n in o.nodes]
        except osmium.InvalidLocationError: continue
        if len(pts) < 2: continue
        if t.get('natural') == 'coastline': coast[o.id] = (o.nodes[0].ref, o.nodes[-1].ref, pts); continue
        hw = t.get('highway'); park = t.get('amenity') in ('parking', 'motorcycle_parking')
        if not (hw or park or t.get('name')): continue
        if park: add_lot('w%d' % o.id, sum(x for x, _ in pts) / len(pts), sum(y for _, y in pts) / len(pts), t)
        hit = [p for p in P if any(p.has(x, y) for x, y in pts)]
        for p in hit:
            if hw and not (args.motorable and hw in NOT_MOTORABLE):
                n = p.write('roads.geojson', {'type': 'Feature', 'properties': {'name': t.get('name', ''), 'kind': hw}, 'geometry': {'type': 'LineString', 'coordinates': [[r6(x), r6(y)] for x, y in pts]}})
                p.kind_bytes[hw] = p.kind_bytes.get(hw, 0) + n
            if park: p.parking.append(parking_record(str(o.id), sum(x for x, _ in pts) / len(pts), sum(y for _, y in pts) / len(pts), t))
            if t.get('name') and (hw or park or 'natural' not in t): p.place(t['name'] if hw else branch_name(t), *pts[len(pts) // 2], poi=is_poi(t))
    elif o.is_area():
        water, green = WATER(t), (not WATER(t)) and GREEN(t)
        if not (water or green): continue
        polys = []
        try:
            for outer in o.outer_rings():
                ring = [[r6(n.lon), r6(n.lat)] for n in outer]
                holes = [[[r6(n.lon), r6(n.lat)] for n in inner] for inner in o.inner_rings(outer)]
                polys.append([ring] + holes)
        except osmium.InvalidLocationError: continue
        if not polys: continue
        xs = [c[0] for poly in polys for c in poly[0]]; ys = [c[1] for poly in polys for c in poly[0]]
        box = (min(xs), min(ys), max(xs), max(ys))
        if (box[2] - box[0]) * (box[3] - box[1]) < 4e-8: continue  # skip ponds/lawns smaller than ~20 m x 20 m
        for p in packs_box(box):
            p.write('water.geojson' if water else 'green.geojson', {'type': 'Feature', 'properties': {'kind': t.get('natural') or t.get('landuse') or t.get('leisure') or t.get('waterway')}, 'geometry': {'type': 'MultiPolygon', 'coordinates': polys}})
print(f'Scan done: {seen:,} objects in {time.time()-started:.0f}s; {len(coast):,} coastline ways', flush=True)

# Coastline ways join end-to-end into closed rings with land on the left (counter-clockwise).
def land_rings():
    by_start = {}
    for wid, (a, b, pts) in coast.items(): by_start.setdefault(a, []).append(wid)
    used = set(); rings = []; open_chains = 0
    for wid in coast:
        if wid in used: continue
        used.add(wid); a, b, pts = coast[wid]; chain = list(pts); start = a; end = b
        while end != start:
            nxt = next((w for w in by_start.get(end, []) if w not in used), None)
            if nxt is None: break
            used.add(nxt); _, end, more = coast[nxt]; chain.extend(more[1:])
        if end == start and len(chain) >= 4: rings.append(chain)
        else: open_chains += 1
    return rings, open_chains

if not args.no_landcover:
    from shapely.geometry import Polygon, box as sbox, mapping
    from shapely.ops import unary_union
    rings, open_chains = land_rings()
    land, holes = [], []
    for r in rings:
        poly = Polygon(r)
        if not poly.is_valid: poly = poly.buffer(0)
        (land if Polygon(r).exterior.is_ccw else holes).append(poly)
    print(f'Coastline: {len(rings):,} closed rings ({len(land):,} land, {len(holes):,} inner water), {open_chains} open chains', flush=True)
    for p in P:
        clip = unary_union([sbox(*a) for a in p.areas])
        parts = [g for g in land if g.intersects(clip)]
        feats = []
        for g in parts:
            g = g.intersection(clip).simplify(0.00003, preserve_topology=True)  # ~3 m
            if g.is_empty or g.area < 4e-8: continue
            feats.append({'type': 'Feature', 'properties': {'kind': 'land'}, 'geometry': json.loads(json.dumps(mapping(g)), parse_float=lambda s: r6(float(s)))})
        for g in holes:
            if g.intersects(clip):
                g = g.intersection(clip).simplify(0.00003, preserve_topology=True)
                if not g.is_empty and g.area >= 4e-8: feats.append({'type': 'Feature', 'properties': {'kind': 'water'}, 'geometry': json.loads(json.dumps(mapping(g)), parse_float=lambda s: r6(float(s)))})
        (p.dir / 'land.geojson').write_text(json.dumps({'type': 'FeatureCollection', 'features': feats}, separators=(',', ':')), encoding='utf-8')
        p.count['land.geojson'] = len(feats)

built = datetime.datetime.now(datetime.timezone.utc).isoformat()
stats = {}
for p in P:
    p.close()
    # Unreviewed/general/private parking must not imply motorcycle eligibility or public access.
    parking = [x for x in p.parking if x['access'] not in ('private', 'no', 'permit')]
    (p.dir / 'parking.json').write_text(json.dumps(parking, separators=(',', ':'), ensure_ascii=False), encoding='utf-8')
    s = p.spec
    meta = {'id': s['id'], 'name': s['name'], 'bounds': p.bounds, 'areas': p.areas, 'built': built, 'source': 'OpenStreetMap / Geofabrik ' + pathlib.Path(args.pbf).name,
            'license': 'ODbL-1.0', 'attribution': '© OpenStreetMap contributors', 'roads': p.count['roads.geojson'], 'places': p.count['places.json'], 'parking': len(parking),
            'note': 'Prices not verified; unknown fees omitted. Route access requires field review.', 'display_name': s.get('display_name', s['name'])}
    for k in ('home', 'demo'):
        if k in s: meta[k] = s[k]
    (p.dir / 'region.json').write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding='utf-8')
    stats[p.id] = {'files': {f.name: f.stat().st_size for f in sorted(p.dir.iterdir()) if f.is_file()}, 'counts': p.count,
                   'road_bytes_by_kind': dict(sorted(p.kind_bytes.items(), key=lambda kv: -kv[1]))}
(out / 'pack-stats.json').write_text(json.dumps(stats, indent=1))
print(json.dumps({k: v['files'] for k, v in stats.items()}, indent=1))
