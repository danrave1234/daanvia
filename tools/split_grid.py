"""Split the densest grid cells into 0.25 deg quarters so a framed city fits the app's road-memory cap.

usage: python tools/split_grid.py dist/areas [--over-mb 15] [--towns app/src/main/assets/overview/towns.json]

Run after tools/build_grid.py. A cell whose roads.geojson is over --over-mb becomes up to four
cell_<minLon>_<minLat>_q.zip files, each holding exactly one level-2 tile, with "deg": 0.25 in the index.
In those dense cells only, roads.geojson drops unnamed service roads (driveways, parking aisles) and keeps
5 decimals (~1 m). They stay in the routing graph, so destinations on them still route. Other cells keep
their files byte for byte; every index entry gains "deg". Features stay in exactly one cell, by first point.
"""
import argparse, hashlib, json, math, pathlib, zipfile
from shapely.geometry import shape, box as sbox, mapping

ap = argparse.ArgumentParser(); ap.add_argument('dist')
ap.add_argument('--over-mb', type=float, default=15); ap.add_argument('--towns', default='')
args = ap.parse_args(); dist = pathlib.Path(args.dist); Q = 0.25
index_file = dist / 'areas-index.json'; index = json.loads(index_file.read_text(encoding='utf-8'))
towns = json.loads(pathlib.Path(args.towns).read_text(encoding='utf-8')) if args.towns else []
js = lambda x: json.dumps(x, separators=(',', ':'), ensure_ascii=False)
fc = lambda feats: '{"type":"FeatureCollection","features":[' + ','.join(feats) + ']}'
arr = lambda items: '[' + ','.join(items) + ']'
def quarter(lon, lat): return (math.floor((lon + 180) / Q + 1e-9), math.floor((lat + 90) / Q + 1e-9))
def qbox(k): return [round(k[0] * Q - 180, 6), round(k[1] * Q - 90, 6), round((k[0] + 1) * Q - 180, 6), round((k[1] + 1) * Q - 90, 6)]
def sha256(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''): h.update(b)
    return h.hexdigest()
def r6(g): return json.loads(json.dumps(mapping(g)), parse_float=lambda s: round(float(s), 6))

cells, removed = [], []
for c in index['cells']:
    c.setdefault('deg', index['cell_deg'])
    if c['roads_bytes'] <= args.over_mb * 1e6 or c['deg'] <= Q: cells.append(c); continue
    z = zipfile.ZipFile(dist / c['file']); parent = c['box']
    keys = [quarter(parent[0] + Q * i + Q / 2, parent[1] + Q * j + Q / 2) for j in range(2) for i in range(2)]
    kx0, ky0 = keys[0]; pick = lambda lon, lat: (min(max(quarter(lon, lat)[0], kx0), kx0 + 1), min(max(quarter(lon, lat)[1], ky0), ky0 + 1))  # rounding can land a point on the edge
    part = {k: {'tiles': [], 'roads': [], 'places': [], 'parking': [], 'lots': [], 'gates': [], 'land': [], 'water': []} for k in keys}
    for name in z.namelist():
        if not name.startswith('valhalla/2/'): continue
        tid = int(''.join(pathlib.PurePosixPath(name[len('valhalla/2/'):]).with_suffix('').parts)); row, col = divmod(tid, 1440)
        part[quarter(-180 + col * Q + Q / 2, -90 + row * Q + Q / 2)]['tiles'].append((name, z.read(name)))
    for f in json.loads(z.read('roads.geojson'))['features']:
        if f['properties']['kind'] == 'service' and not f['properties']['name']: continue
        pts = [[round(x, 5), round(y, 5)] for x, y in f['geometry']['coordinates']]; f['geometry']['coordinates'] = pts
        part[pick(*pts[0])]['roads'].append(js(f))
    for n, key in (('places.json', 'places'), ('parking.json', 'parking'), ('lots.json', 'lots'), ('gates.json', 'gates')):
        if n not in z.namelist(): continue  # cells built before gates.json
        for x in json.loads(z.read(n)): part[pick(x['lon'], x['lat'])][key].append(js(x))
    for n, key in (('land.geojson', 'land'), ('water.geojson', 'water')):
        for f in json.loads(z.read(n))['features']:
            g = shape(f['geometry'])
            for k in keys:
                p = g.intersection(sbox(*qbox(k)))
                if not p.is_empty and p.area >= 4e-8: part[k][key].append(js({'type': 'Feature', 'properties': f['properties'], 'geometry': r6(p)}))
    z.close()
    for k in keys:
        p = part[k]
        if not (p['tiles'] or p['roads'] or p['land']): continue
        b = qbox(k); cid = 'cell_%.2f_%.2f_q' % (b[0], b[1]); target = dist / (cid + '.zip'); roads = fc(p['roads'])
        with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as out:
            for name, data in sorted(p['tiles']): out.writestr(name, data)
            out.writestr('roads.geojson', roads); out.writestr('places.json', arr(p['places'])); out.writestr('parking.json', arr(p['parking']))
            out.writestr('lots.json', arr(p['lots'])); out.writestr('gates.json', arr(p['gates'])); out.writestr('land.geojson', fc(p['land'])); out.writestr('water.geojson', fc(p['water']))
        inside = lambda t: b[0] <= t['lon'] < b[2] and b[1] <= t['lat'] < b[3]
        mid = ((b[1] + b[3]) / 2, (b[0] + b[2]) / 2); here = [t for t in towns if inside(t)]
        label = c['label'] if any(t['name'] == c['label'] for t in here) else min(here, key=lambda t: (t['kind'] != 'city', (t['lat'] - mid[0]) ** 2 + (t['lon'] - mid[1]) ** 2))['name'] if here else ''
        cells.append({'id': cid, 'box': b, 'file': target.name, 'bytes': target.stat().st_size, 'sha256': sha256(target),
                      'roads_bytes': len(roads.encode('utf-8')), 'label': label, 'needs': c['needs'], 'deg': Q})
    removed.append(c['file']); print(f"{c['id']} ({c['label']}, {c['roads_bytes']/1e6:.1f} MB roads) -> quarters", flush=True)
index['cells'] = cells
tmp = index_file.with_suffix('.json.part'); tmp.write_text(json.dumps(index, indent=1, ensure_ascii=False) + '\n', encoding='utf-8'); tmp.replace(index_file)
for f in removed: (dist / f).unlink()
print(f'{len(cells)} cells now; removed {len(removed)} dense cells', flush=True)
