"""Check downloadable packs exactly as a phone would receive them, and route each pack's demo.

usage (needs `pip install pyvalhalla==3.9.0`): python tools/verify_packs.py dist/packs [--work /tmp/daanvia-verify]

For every entry in packs-index.json: the zip's size and sha256 match the index, every file matches the
bytes/sha256 in its region.json, and the demo from -> to routes with the app's request (motor_scooter, no
highways or tolls, 80 km/h). The demo must stay inside the pack's areas. Prints a JSON report.
"""
import argparse, hashlib, json, pathlib, shutil, tempfile, time, zipfile
import valhalla

ap = argparse.ArgumentParser(); ap.add_argument('dist'); ap.add_argument('--work', default='')
args = ap.parse_args(); dist = pathlib.Path(args.dist)
index = json.loads((dist / 'packs-index.json').read_text(encoding='utf-8'))
work = pathlib.Path(args.work or tempfile.mkdtemp(prefix='daanvia-verify-')); work.mkdir(parents=True, exist_ok=True)
def inside(areas, lat, lon): return any(a[0] <= lon <= a[2] and a[1] <= lat <= a[3] for a in areas)
report = []
for e in index['packs']:
    z = dist / e['file']; raw = z.read_bytes(); row = {'id': e['id'], 'zip_bytes': len(raw)}
    assert len(raw) == e['bytes'] and hashlib.sha256(raw).hexdigest() == e['sha256'], e['id'] + ': zip does not match packs-index.json'
    d = work / e['id']; shutil.rmtree(d, ignore_errors=True); d.mkdir()
    with zipfile.ZipFile(z) as f: f.extractall(d)
    meta = json.loads((d / 'region.json').read_text(encoding='utf-8'))
    for n, spec in meta['files'].items():
        b = (d / n).read_bytes(); assert len(b) == spec['bytes'] and hashlib.sha256(b).hexdigest() == spec['sha256'], f"{e['id']}: {n} checksum"
    row['roads_geojson_bytes'] = meta['files']['roads.geojson']['bytes']; row['unpacked_bytes'] = e['unpacked_bytes']
    demo = meta['demo']; assert inside(meta['areas'], *demo['from']) and inside(meta['areas'], *demo['to']), e['id'] + ': demo outside pack'
    cfg = json.loads((d / 'valhalla-template.json').read_text()); m = cfg['mjolnir']
    m.update(tile_extract=str(d / 'valhalla_tiles.tar'), tile_dir=''); m.pop('tile_url', None)
    for k in ('traffic_extract', 'admin', 'landmarks', 'timezone'): m[k] = ''
    t = time.time()
    try:
        r = valhalla.Actor(cfg).route({'locations': [{'lat': demo['from'][0], 'lon': demo['from'][1]}, {'lat': demo['to'][0], 'lon': demo['to'][1]}],
                                       'costing': 'motor_scooter', 'language': 'en-US', 'costing_options': {'motor_scooter': {'use_highways': 0, 'use_tolls': 0, 'top_speed': 80}}})
        s = r['trip']['summary']; legs = r['trip']['legs']
        row['demo'] = {'name': demo['name'], 'km': s['length'], 'minutes': round(s['time'] / 60, 1), 'maneuvers': len(legs[0]['maneuvers']), 'ms': round((time.time() - t) * 1000)}
    except Exception as x: row['demo'] = {'name': demo['name'], 'failed': str(x)}
    report.append(row); shutil.rmtree(d)
print(json.dumps(report, indent=1, ensure_ascii=False))
