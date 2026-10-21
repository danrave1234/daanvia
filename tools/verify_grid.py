"""Merge grid cells the way the phone does and route on them with a tile_dir graph (no tar).

usage (needs `pip install pyvalhalla==3.9.0`): python tools/verify_grid.py dist/areas [--work /tmp/daanvia-grid]

Each case picks the cells touching a box, checks every zip against areas-index.json, unzips them with their
base_l1/base_l0 files into one folder, concatenates roads/places/parking/lots/land/water as the app does
(the merged JSON must parse and hold no duplicate ids), then routes with the app's scooter request.
Edges: wherever two of the case's cells share an edge, every road crossing it must be in both cells' own
roads.geojson, crossing at the same point (so it shows whichever of the two is downloaded). Unnamed service
roads are left out of that comparison: split_grid.py drops them from the dense quarters.
The index must name its generation, and every file name must carry the start of its sha256.
"""
import argparse, hashlib, json, pathlib, shutil, tempfile, time, zipfile
import valhalla
from shapely.geometry import LineString

ap = argparse.ArgumentParser(); ap.add_argument('dist'); ap.add_argument('--work', default='')
args = ap.parse_args(); dist = pathlib.Path(args.dist)
index = json.loads((dist / 'areas-index.json').read_text(encoding='utf-8')); base = {b['file']: b for b in index['base']}
template = json.loads(pathlib.Path(__file__).with_name('valhalla-template.json').read_text())
for k in ('generation', 'built', 'osm_timestamp', 'package_schema', 'engine'): assert index.get(k), 'areas-index.json has no ' + k
for f in [c for c in index['cells']] + index['base']: assert f['file'].endswith('-%s.zip' % f['sha256'][:12]), f['file'] + ' is not named after its sha256'
root = pathlib.Path(args.work or tempfile.mkdtemp(prefix='daanvia-grid-'))
def touches(c, b): return not (c['box'][2] <= b[0] or c['box'][0] >= b[2] or c['box'][3] <= b[1] or c['box'][1] >= b[3])
def checked(name, spec):
    raw = (dist / name).read_bytes(); assert len(raw) == spec['bytes'] and hashlib.sha256(raw).hexdigest() == spec['sha256'], name + ' does not match the index'
    return dist / name
def merge(cells, d):
    shutil.rmtree(d, ignore_errors=True); d.mkdir(parents=True); parts = {}; needs = set(); own = {}
    for c in cells:
        needs.update(c['needs'])
        with zipfile.ZipFile(checked(c['file'], c)) as z:
            for n in z.namelist():
                if n.startswith('valhalla/'): z.extract(n, d)
                else: parts.setdefault(n, []).append(z.read(n).decode('utf-8'))
            own[c['id']] = json.loads(z.read('roads.geojson'))['features']
    for n in sorted(needs):
        with zipfile.ZipFile(checked(n, base[n])) as z: z.extractall(d)
    merged = {}
    for n, texts in parts.items():  # the app's merge: strip each wrapper, join the arrays, wrap once
        if n.endswith('.geojson'):
            head = '{"type":"FeatureCollection","features":['; bodies = [t[len(head):-2] for t in texts]; assert all(t.startswith(head) and t.endswith(']}') for t in texts), n
            merged[n] = json.loads(head + ','.join(b for b in bodies if b) + ']}')['features']
        else:
            assert all(t.startswith('[') and t.endswith(']') for t in texts), n
            merged[n] = json.loads('[' + ','.join(t[1:-1] for t in texts if t[1:-1]) + ']')
    for n in ('parking.json', 'lots.json'):
        ids = [x['id'] for x in merged[n]]; assert len(ids) == len(set(ids)), n + ' has duplicate ids after merge'
    return needs, {n: len(v) for n, v in merged.items()}, own
def shared_edge(a, b):
    """The segment two cell boxes share, ((x0, y0), (x1, y1)), or None."""
    if a[2] == b[0] or b[2] == a[0]:
        x = a[2] if a[2] == b[0] else a[0]; lo, hi = max(a[1], b[1]), min(a[3], b[3])
        return ((x, lo), (x, hi)) if hi > lo else None
    if a[3] == b[1] or b[3] == a[1]:
        y = a[3] if a[3] == b[1] else a[1]; lo, hi = max(a[0], b[0]), min(a[2], b[2])
        return ((lo, y), (hi, y)) if hi > lo else None
    return None
def near_edge(features, edge, tol):
    """(name, kind, line) of the features within tol of the edge, unnamed service roads left out."""
    seg = LineString(edge).buffer(tol); out = []
    for f in features:
        pr = f['properties']
        if pr['kind'] == 'service' and not pr['name']: continue
        g = LineString(f['geometry']['coordinates'])
        if g.intersects(seg): out.append((pr['name'], pr['kind'], g))
    return out
def edges(cells, own, tol=3e-5):
    """Each road crossing a shared edge must be in the other cell too, within tol degrees (~3 m: dense quarters keep 5 decimals)
    of where it crosses. Compared by distance, so a road that ends a hair before the line in one cell still counts."""
    pairs = total = 0; missing = []; example = None
    for i, a in enumerate(cells):
        for b in cells[i + 1:]:
            e = shared_edge(a['box'], b['box'])
            if not e: continue
            pairs += 1; seg = LineString(e); na, nb = near_edge(own[a['id']], e, tol), near_edge(own[b['id']], e, tol)
            for one, other, here, there in ((na, nb, a, b), (nb, na, b, a)):
                for n, k, g in one:
                    hit = g.intersection(seg)
                    for p in getattr(hit, 'geoms', [hit]):
                        if p.geom_type != 'Point': continue
                        total += here is a
                        if not any(n == n2 and k == k2 and g2.distance(p) <= tol for n2, k2, g2 in other):
                            missing.append({'road': n or '(unnamed)', 'kind': k, 'at': [round(p.y, 6), round(p.x, 6)], 'in': here['id'], 'not_in': there['id']})
                        elif example is None and n and k in ('trunk', 'primary', 'secondary'): example = {'road': n, 'kind': k, 'crosses_at': [round(p.y, 6), round(p.x, 6)], 'cells': [a['id'], b['id']]}
    return {'shared_edges': pairs, 'crossings': total, 'missing': len(missing), 'missing_examples': missing[:5], 'example': example}
def route(d, a, b):
    cfg = json.loads(json.dumps(template)); m = cfg['mjolnir']
    m.update(tile_dir=str(d / 'valhalla'), tile_extract=''); m.pop('tile_url', None)
    for k in ('traffic_extract', 'admin', 'landmarks', 'timezone'): m[k] = ''
    t = time.time()
    try:
        r = valhalla.Actor(cfg).route({'locations': [{'lat': a[0], 'lon': a[1]}, {'lat': b[0], 'lon': b[1]}], 'costing': 'motor_scooter', 'language': 'en-US',
                                       'costing_options': {'motor_scooter': {'use_highways': 0, 'use_tolls': 0, 'top_speed': 80}}})
        s = r['trip']['summary']; return {'km': s['length'], 'minutes': round(s['time'] / 60, 1), 'maneuvers': len(r['trip']['legs'][0]['maneuvers']), 'ms': round((time.time() - t) * 1000)}
    except Exception as x: return {'failed': str(x)[:120]}
cases = [  # label, area framed (minLon,minLat,maxLon,maxLat), from, to
    ('Metro Cebu: Cebu demo', [123.60, 10.10, 124.08, 10.62], (10.3157, 123.8854), (10.2934, 123.9019)),
    ('Metro Cebu: Cebu City to Danao (crosses lon 124.0 and lat 10.5)', [123.60, 10.10, 124.08, 10.62], (10.3157, 123.8854), (10.5203, 124.0272)),
    ('Metro Cebu: Cebu City to Shangri-La Mactan (crosses lon 124.0)', [123.60, 10.10, 124.08, 10.62], (10.3157, 123.8854), (10.3087, 124.0190)),
    ('Metro Manila: Megamall to Alabang (crosses lat 14.5)', [120.90, 14.35, 121.13, 14.78], (14.5850, 121.0561), (14.4235, 121.0300)),
    ('Angeles-Clark: Angeles City Hall to Clark airport', [120.48, 15.08, 120.66, 15.28], (15.1654, 120.6081), (15.1881, 120.5482)),
    ('One cell only: Cebu City to Danao, destination cell not downloaded', [123.80, 10.20, 123.95, 10.40], (10.3157, 123.8854), (10.5203, 124.0272)),
]
report = []
for label, area, a, b in cases:
    cells = [c for c in index['cells'] if touches(c, area)]; d = root / str(len(report))
    needs, counts, own = merge(cells, d)
    report.append({'case': label, 'cells': [c['id'] for c in cells], 'base': sorted(needs), 'roads_bytes': sum(c['roads_bytes'] for c in cells), 'under_app_cap_60MB': sum(c['roads_bytes'] for c in cells) <= 60 * 1024 * 1024,
                   'download_bytes': sum(c['bytes'] for c in cells) + sum(base[n]['bytes'] for n in needs), 'merged_counts': counts, 'edges': edges(cells, own), 'route': route(d, a, b)})
    shutil.rmtree(d)
print(json.dumps({'generation': index['generation'], 'osm_timestamp': index['osm_timestamp'], 'engine': index['engine'], 'cases': report}, indent=1, ensure_ascii=False))
bad = [r['case'] for r in report if r['edges']['missing'] or ('failed' in r['route']) != ('not downloaded' in r['case'])]  # that case must not route
if bad: raise SystemExit('FAILED: ' + '; '.join(bad))
