"""Merge grid cells the way the phone does and route on them with a tile_dir graph (no tar).

usage (needs `pip install pyvalhalla==3.9.0`): python tools/verify_grid.py dist/areas [--work /tmp/daanvia-grid]

Each case picks the cells touching a box, checks every zip against areas-index.json, unzips them with their
base_l1/base_l0 files into one folder, concatenates roads/places/parking/lots/land/water as the app does
(the merged JSON must parse and hold no duplicate ids), then routes with the app's scooter request.
"""
import argparse, hashlib, json, pathlib, shutil, tempfile, time, zipfile
import valhalla

ap = argparse.ArgumentParser(); ap.add_argument('dist'); ap.add_argument('--work', default='')
args = ap.parse_args(); dist = pathlib.Path(args.dist)
index = json.loads((dist / 'areas-index.json').read_text(encoding='utf-8')); base = {b['file']: b for b in index['base']}
template = json.loads(pathlib.Path(__file__).with_name('valhalla-template.json').read_text())
root = pathlib.Path(args.work or tempfile.mkdtemp(prefix='daanvia-grid-'))
def touches(c, b): return not (c['box'][2] <= b[0] or c['box'][0] >= b[2] or c['box'][3] <= b[1] or c['box'][1] >= b[3])
def checked(name, spec):
    raw = (dist / name).read_bytes(); assert len(raw) == spec['bytes'] and hashlib.sha256(raw).hexdigest() == spec['sha256'], name + ' does not match the index'
    return dist / name
def merge(cells, d):
    shutil.rmtree(d, ignore_errors=True); d.mkdir(parents=True); parts = {}; needs = set()
    for c in cells:
        needs.update(c['needs'])
        with zipfile.ZipFile(checked(c['file'], c)) as z:
            for n in z.namelist():
                if n.startswith('valhalla/'): z.extract(n, d)
                else: parts.setdefault(n, []).append(z.read(n).decode('utf-8'))
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
    return needs, {n: len(v) for n, v in merged.items()}
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
    needs, counts = merge(cells, d)
    report.append({'case': label, 'cells': [c['id'] for c in cells], 'base': sorted(needs), 'roads_bytes': sum(c['roads_bytes'] for c in cells), 'under_app_cap_60MB': sum(c['roads_bytes'] for c in cells) <= 60 * 1024 * 1024,
                   'download_bytes': sum(c['bytes'] for c in cells) + sum(base[n]['bytes'] for n in needs), 'merged_counts': counts, 'route': route(d, a, b)})
    shutil.rmtree(d)
print(json.dumps(report, indent=1, ensure_ascii=False))
