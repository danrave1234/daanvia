"""Cut the country into downloadable grid cells that riders pick on the map (Google Maps offline style).

usage: python tools/build_grid.py philippines-latest.osm.pbf COUNTRY_TILE_DIR dist/areas
                                  [--overview app/src/main/assets/overview] [--deg 0.5] [--work DIR]

COUNTRY_TILE_DIR is one Valhalla 3.9.0 tile_dir for the whole country (valhalla_build_tiles, no tar).
A cell is DEG x DEG degrees aligned to Valhalla's 0.25 deg level-2 grid (0.5 = four level-2 tiles), and
lives in only one level-1 (1 deg) and one level-0 (4 deg) tile. Output:
  cell_<minLon>_<minLat>.zip  valhalla/2/... (that cell's level-2 tiles, tile_dir layout), roads.geojson
                              (motorable only), places.json, parking.json, lots.json, gates.json, land.geojson, water.geojson
  base_l1_<id>.zip / base_l0_<id>.zip  valhalla/1/... and valhalla/0/..., shared by every cell under them
  areas-index.json            cells with box, file, bytes, sha256, roads_bytes, label and needed base files
Each road, place, parking lot and lot belongs to exactly one cell (its first point), so merging cells by
concatenating the arrays never duplicates anything. Land and water are clipped to the cell box.
--overview writes overview.geojson (land ~150 m + motorway/trunk/primary) and towns.json for choosing an area.
Then run tools/split_grid.py to quarter the densest cells, and tools/verify_grid.py to merge and route.
"""
import argparse, ast, datetime, hashlib, json, math, pathlib, shutil, time, zipfile
import osmium
from shapely.geometry import Polygon, LineString, box as sbox, mapping, shape
from shapely.ops import unary_union, linemerge
from shapely.strtree import STRtree

ap = argparse.ArgumentParser()
ap.add_argument('pbf'); ap.add_argument('tiles'); ap.add_argument('out')
ap.add_argument('--overview', default=''); ap.add_argument('--deg', type=float, default=0.5); ap.add_argument('--work', default='')
args = ap.parse_args()
DEG = args.deg; assert abs(DEG / 0.25 - round(DEG / 0.25)) < 1e-9 and 1 % DEG == 0, 'cell size must be a multiple of 0.25 that divides 1 degree'
tiles = pathlib.Path(args.tiles); out = pathlib.Path(args.out); out.mkdir(parents=True, exist_ok=True)
work = pathlib.Path(args.work or out / '_work'); shutil.rmtree(work, ignore_errors=True); (work / 'roads').mkdir(parents=True)
NOT_MOTORABLE = {'footway', 'path', 'steps', 'pedestrian', 'cycleway', 'bridleway', 'corridor', 'elevator', 'platform',
                 'proposed', 'construction', 'abandoned', 'razed', 'disused', 'bus_stop', 'via_ferrata'}
MAJOR = ('motorway', 'trunk', 'primary')
r6 = lambda v: round(v, 6)
_lots_src = pathlib.Path(__file__).with_name('build_lots.py').read_text(encoding='utf-8')  # lots.json record shape
exec(compile(ast.Module([n for n in ast.parse(_lots_src).body if isinstance(n, ast.FunctionDef) and n.name == 'record'], []), 'build_lots.py', 'exec'))

def cell_of(lon, lat): return (math.floor((lon + 180) / DEG + 1e-9), math.floor((lat + 90) / DEG + 1e-9))
def cell_box(c): return [round(c[0] * DEG - 180, 6), round(c[1] * DEG - 90, 6), round((c[0] + 1) * DEG - 180, 6), round((c[1] + 1) * DEG - 90, 6)]
def cell_id(c): b = cell_box(c); return 'cell_%.2f_%.2f' % (b[0], b[1])

class Cell:
    def __init__(self, c): self.c = c; self.roads = None; self.nroads = 0; self.places = []; self.seen = set(); self.parking = []; self.lots = []; self.water = []; self.towns = []; self.gates = []
    def road(self, feat):
        if self.roads is None: self.roads = open(work / 'roads' / (cell_id(self.c) + '.geojson'), 'w', encoding='utf-8', newline='\n')
        self.roads.write((',' if self.nroads else '') + json.dumps(feat, separators=(',', ':'), ensure_ascii=False)); self.nroads += 1
    def place(self, name, lon, lat, poi=False):
        # Streets repeat their name segment after segment: one entry per name per ~2 km. Shops and restaurants are each
        # their own place (a chain has many branches close together): one per name per ~50 m.
        if not any(ch.isalpha() for ch in name): return  # house or stall numbers ("5", "12-A")
        key = (name, round(lat * 2000), round(lon * 2000)) if poi else (name, round(lat * 50), round(lon * 50))
        if key not in self.seen: self.seen.add(key); self.places.append(js({'name': name, 'lon': r6(lon), 'lat': r6(lat)}))
cells = {}
# Only shops, amenities (parking too) and tourism are branches; railways, rivers and other named lines repeat per segment.
def is_poi(t): return 'highway' not in t and any(k in t for k in ('amenity', 'shop', 'tourism'))
js = lambda x: json.dumps(x, separators=(',', ':'), ensure_ascii=False)  # cells keep serialized records to stay small in RAM
GATES = ('gate', 'lift_gate', 'swing_gate', 'sliding_gate', 'entrance', 'toll_booth')
def branch_name(t):
    """OSM names most chain branches just "Jollibee" and puts the branch in branch=: "Jollibee Vito Cruz", like Google Maps."""
    name, branch = t['name'].strip(), t.get('branch', '').strip()
    return name + ' ' + branch if branch and branch.lower() not in name.lower() else name
def cell(lon, lat):
    c = cell_of(lon, lat)
    if c not in cells: cells[c] = Cell(c)
    return cells[c]
def parking_record(oid, lon, lat, t):
    return {'id': oid, 'lon': r6(lon), 'lat': r6(lat), 'name': t.get('name', 'Parking'), 'source': 'OpenStreetMap',
            'motorcycle': t.get('amenity') == 'motorcycle_parking', 'access': t.get('access', 'unknown')}

coast = {}; major = []; towns = []
water_tmp = open(work / 'water.jsonl', 'w', encoding='utf-8')
fp = (osmium.FileProcessor(args.pbf).with_locations('sparse_file_array,' + str(work / 'nodes.idx'))
      .with_areas(osmium.filter.KeyFilter('natural', 'waterway', 'landuse'))
      .with_filter(osmium.filter.KeyFilter('highway', 'amenity', 'natural', 'name', 'waterway', 'landuse', 'place')))
started = time.time()
for o in fp:
    t = dict(o.tags)
    if o.is_node():
        if not o.location.valid(): continue
        lon, lat = o.location.lon, o.location.lat; k = cell(lon, lat); park = t.get('amenity') in ('parking', 'motorcycle_parking')
        if park: k.parking.append((t.get('access', 'unknown'), js(parking_record(str(o.id), lon, lat, t)))); k.lots.append(js(record('n%d' % o.id, lon, lat, t)))
        # Gates and guardhouse barriers on roads, so the app can say when a route goes through a subdivision gate.
        if t.get('barrier') in GATES and t.get('access') not in ('yes', 'permissive'):
            k.gates.append(js({'lat': r6(lat), 'lon': r6(lon), 'access': t.get('access', ''), 'name': t.get('name', '')}))
        if t.get('name') and any(x in t for x in ('amenity', 'tourism', 'shop', 'place', 'public_transport')): k.place(branch_name(t), lon, lat, poi='place' not in t)
        if t.get('place') in ('city', 'town') and t.get('name'):
            try: pop = int(str(t.get('population', '0')).replace(',', '').split(';')[0] or 0)
            except ValueError: pop = 0
            towns.append({'name': t['name'], 'lat': r6(lat), 'lon': r6(lon), 'kind': t['place'], 'pop': pop}); k.towns.append(towns[-1])
    elif o.is_way():
        try: pts = [(n.location.lon, n.location.lat) for n in o.nodes]
        except osmium.InvalidLocationError: continue
        if len(pts) < 2: continue
        if t.get('natural') == 'coastline': coast[o.id] = (o.nodes[0].ref, o.nodes[-1].ref, pts); continue
        hw = t.get('highway'); park = t.get('amenity') in ('parking', 'motorcycle_parking')
        if hw and hw not in NOT_MOTORABLE:
            cell(*pts[0]).road({'type': 'Feature', 'properties': {'name': t.get('name', ''), 'kind': hw}, 'geometry': {'type': 'LineString', 'coordinates': [[r6(x), r6(y)] for x, y in pts]}})
            if hw in MAJOR: major.append((hw, [(round(x, 5), round(y, 5)) for x, y in pts]))
        if park:
            cx, cy = sum(x for x, _ in pts) / len(pts), sum(y for _, y in pts) / len(pts); k = cell(cx, cy)
            k.parking.append((t.get('access', 'unknown'), js(parking_record(str(o.id), cx, cy, t)))); k.lots.append(js(record('w%d' % o.id, cx, cy, t)))
        if t.get('name') and (hw or park or 'natural' not in t): cell(*pts[len(pts) // 2]).place(t['name'] if hw else branch_name(t), *pts[len(pts) // 2], poi=is_poi(t))
    elif o.is_area():
        if not (t.get('natural') == 'water' or t.get('waterway') == 'riverbank' or t.get('landuse') in ('reservoir', 'basin')): continue
        try: polys = [[[[r6(n.lon), r6(n.lat)] for n in outer]] + [[[r6(n.lon), r6(n.lat)] for n in inner] for inner in o.inner_rings(outer)] for outer in o.outer_rings()]
        except osmium.InvalidLocationError: continue
        xs = [c[0] for p in polys for c in p[0]]; ys = [c[1] for p in polys for c in p[0]]
        if not xs or (max(xs) - min(xs)) * (max(ys) - min(ys)) < 4e-8: continue  # skip ponds under ~20 m x 20 m
        water_tmp.write(json.dumps({'kind': t.get('natural') or t.get('landuse') or t.get('waterway'), 'coordinates': polys}) + '\n')
water_tmp.close()
for k in cells.values():
    if k.roads: k.roads.close()
print(f'Scan {time.time()-started:.0f}s: {len(cells)} cells with data, {len(coast):,} coastline ways', flush=True)

# Land: coastline ways join into closed rings, land on the left (counter-clockwise).
by_start = {}
for wid, (a, b, pts) in coast.items(): by_start.setdefault(a, []).append(wid)
used = set(); land = []
for wid in coast:
    if wid in used: continue
    used.add(wid); a, end, pts = coast[wid]; chain = list(pts)
    while end != a:
        nxt = next((w for w in by_start.get(end, []) if w not in used), None)
        if nxt is None: break
        used.add(nxt); _, end, more = coast[nxt]; chain.extend(more[1:])
    if end == a and len(chain) >= 4:
        g = Polygon(chain)
        if g.exterior.is_ccw: land.append(g if g.is_valid else g.buffer(0))
tree = STRtree(land)
print(f'Land: {len(land):,} islands', flush=True)
tile_files = {lvl: {int(''.join(p.relative_to(tiles / str(lvl)).with_suffix('').parts)): p for p in (tiles / str(lvl)).rglob('*.gph')} for lvl in (0, 1, 2)}
l2_cells = {}
for tid, p in tile_files[2].items():
    row, col = divmod(tid, 1440); l2_cells.setdefault(cell_of(-180 + col * 0.25 + 0.01, -90 + row * 0.25 + 0.01), []).append(p)
keep = set(l2_cells) | {c for c, k in cells.items() if k.nroads}
lon0, lat0 = cell_of(min(g.bounds[0] for g in land), min(g.bounds[1] for g in land)) if land else (0, 0); lon1, lat1 = cell_of(max(g.bounds[2] for g in land), max(g.bounds[3] for g in land)) if land else (-1, -1)
for cx in range(lon0, lon1 + 1):
    for cy in range(lat0, lat1 + 1):
        b = sbox(*cell_box((cx, cy)))
        if any(land[i].intersects(b) for i in tree.query(b)): keep.add((cx, cy))
for c in keep:
    if c not in cells: cells[c] = Cell(c)
def geo(g): return json.loads(json.dumps(mapping(g)), parse_float=lambda s: r6(float(s)))
with open(work / 'water.jsonl', encoding='utf-8') as f:
    for line in f:
        w = json.loads(line); g = shape({'type': 'MultiPolygon', 'coordinates': w['coordinates']})
        if not g.is_valid: g = g.buffer(0)
        a = cell_of(g.bounds[0], g.bounds[1]); b = cell_of(g.bounds[2], g.bounds[3])
        for cx in range(a[0], b[0] + 1):
            for cy in range(a[1], b[1] + 1):
                if (cx, cy) not in keep: continue
                part = g.intersection(sbox(*cell_box((cx, cy))))
                if not part.is_empty and part.area >= 4e-8: cells[(cx, cy)].water.append(js({'type': 'Feature', 'properties': {'kind': w['kind']}, 'geometry': geo(part)}))

def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for blk in iter(lambda: f.read(1 << 20), b''): h.update(blk)
    return h.hexdigest()
def l1_of(c): b = cell_box(c); return math.floor(b[1] + 90 + 1e-9) * 360 + math.floor(b[0] + 180 + 1e-9)
def l0_of(c): b = cell_box(c); return math.floor((b[1] + 90) / 4 + 1e-9) * 90 + math.floor((b[0] + 180) / 4 + 1e-9)
fc = lambda feats: '{"type":"FeatureCollection","features":[' + ','.join(x if isinstance(x, str) else js(x) for x in feats) + ']}'
arr = lambda items: '[' + ','.join(x if isinstance(x, str) else js(x) for x in items) + ']'
built = datetime.datetime.now(datetime.timezone.utc).isoformat(); index = []; needed_base = set()
for c in sorted(keep):
    k = cells[c]; cid = cell_id(c); b = sbox(*cell_box(c))
    landf = []
    for i in tree.query(b):
        part = land[i].intersection(b).simplify(0.00003, preserve_topology=True)  # ~3 m
        if not part.is_empty and part.area >= 4e-8: landf.append({'type': 'Feature', 'properties': {'kind': 'land'}, 'geometry': geo(part)})
    roads_file = work / 'roads' / (cid + '.geojson')
    roads = '{"type":"FeatureCollection","features":[' + (roads_file.read_text(encoding='utf-8') if roads_file.exists() else '') + ']}'
    parking = [rec for acc, rec in k.parking if acc not in ('private', 'no', 'permit')]  # pins never show private lots; lots.json keeps them
    needs = [n for n, ok in (('base_l1_%d.zip' % l1_of(c), l1_of(c) in tile_files[1]), ('base_l0_%d.zip' % l0_of(c), l0_of(c) in tile_files[0])) if ok]
    needed_base.update(needs)
    target = out / (cid + '.zip')
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in sorted(l2_cells.get(c, [])): z.write(p, 'valhalla/' + p.relative_to(tiles).as_posix())
        z.writestr('roads.geojson', roads); z.writestr('places.json', arr(k.places)); z.writestr('parking.json', arr(parking)); z.writestr('lots.json', arr(k.lots)); z.writestr('gates.json', arr(k.gates))
        z.writestr('land.geojson', fc(landf)); z.writestr('water.geojson', fc(k.water))
    label = sorted(k.towns, key=lambda x: (x['kind'] != 'city', -x['pop'], x['name']))
    index.append({'id': cid, 'box': cell_box(c), 'file': target.name, 'bytes': target.stat().st_size, 'sha256': sha256(target),
                  'roads_bytes': len(roads.encode('utf-8')), 'label': label[0]['name'] if label else '', 'needs': needs})
base = []
for lvl in (1, 0):
    for tid, p in sorted(tile_files[lvl].items()):
        name = 'base_l%d_%d.zip' % (lvl, tid); target = out / name
        with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z: z.write(p, 'valhalla/' + p.relative_to(tiles).as_posix())
        base.append({'file': name, 'bytes': target.stat().st_size, 'sha256': sha256(target)})
(out / 'areas-index.json').write_text(json.dumps({'version': 2, 'cell_deg': DEG, 'built': built, 'source': 'OpenStreetMap / Geofabrik ' + pathlib.Path(args.pbf).name,
    'license': 'ODbL-1.0', 'attribution': '© OpenStreetMap contributors', 'cells': index, 'base': base}, indent=1, ensure_ascii=False) + '\n', encoding='utf-8')
print(f'{len(index)} cells, {len(base)} base files, {sum(x["bytes"] for x in index)+sum(x["bytes"] for x in base):,} bytes; base files nobody needs: {sorted({x["file"] for x in base}-needed_base)[:5]}', flush=True)

if args.overview:
    ov = pathlib.Path(args.overview); ov.mkdir(parents=True, exist_ok=True); feats = []
    for g in land:
        s = g.simplify(0.0014, preserve_topology=True)  # ~150 m
        if s.area >= 2e-6: feats.append({'type': 'Feature', 'properties': {'kind': 'land'}, 'geometry': json.loads(json.dumps(mapping(s)), parse_float=lambda v: round(float(v), 4))})
    for kind in MAJOR:
        merged = linemerge([LineString(p) for k2, p in major if k2 == kind])
        for line in getattr(merged, 'geoms', [merged]):
            s = line.simplify(0.0008)  # ~90 m
            if s.length >= 0.002: feats.append({'type': 'Feature', 'properties': {'kind': kind}, 'geometry': json.loads(json.dumps(mapping(s)), parse_float=lambda v: round(float(v), 4))})
    (ov / 'overview.geojson').write_text(fc(feats), encoding='utf-8')
    (ov / 'towns.json').write_text(arr([{k2: x[k2] for k2 in ('name', 'lat', 'lon', 'kind')} for x in sorted(towns, key=lambda x: (x['kind'] != 'city', x['name']))]), encoding='utf-8')
    print('overview', (ov / 'overview.geojson').stat().st_size, 'bytes,', len(towns), 'towns', flush=True)
del fp; import gc; gc.collect(); shutil.rmtree(work, ignore_errors=True)
