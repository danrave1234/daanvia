"""Zip built packs for download and write packs-index.json.

usage: python tools/package_packs.py DATADIR TILEDIR OUTDIR [--packs tools/packs.json] [--kind city] [--only id,id]

DATADIR/<id>/ comes from tools/build_packs.py and TILEDIR/<id>/ from tools/build_pack_tiles.py. Each
OUTDIR/<id>.zip holds the pack files at its root. region.json gets "files" (bytes + sha256 of every other
file), which the app uses to check an unpacked pack. Every entry is deflated: the graph shrinks ~2.5x, which
matters on mobile data. packs-index.json lists every zip with its size and sha256 for the download screen.
"""
import argparse, datetime, hashlib, json, pathlib, zipfile

ap = argparse.ArgumentParser()
ap.add_argument('data'); ap.add_argument('tiles'); ap.add_argument('out')
ap.add_argument('--packs', default=str(pathlib.Path(__file__).with_name('packs.json')))
ap.add_argument('--kind', default='city'); ap.add_argument('--only', default='')
args = ap.parse_args()
data, tiles, out = map(pathlib.Path, (args.data, args.tiles, args.out)); out.mkdir(parents=True, exist_ok=True)
specs = json.loads(pathlib.Path(args.packs).read_text(encoding='utf-8'))['packs']
specs = [s for s in specs if s['id'] in args.only.split(',')] if args.only else [s for s in specs if s.get('kind') == args.kind]
DATA = ('roads.geojson', 'places.json', 'parking.json', 'lots.json', 'land.geojson', 'water.geojson')
GRAPH = ('valhalla_tiles.tar', 'valhalla-template.json')

def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''): h.update(b)
    return h.hexdigest()

index = []
for s in specs:
    pid = s['id']; files = {n: data / pid / n for n in DATA}; files.update({n: tiles / pid / n for n in GRAPH})
    missing = [str(p) for p in files.values() if not p.is_file()]
    if missing: raise SystemExit(f'{pid}: missing {missing}')
    meta = json.loads((data / pid / 'region.json').read_text(encoding='utf-8'))
    meta['files'] = {n: {'bytes': p.stat().st_size, 'sha256': sha256(p)} for n, p in files.items()}
    meta['landcover'] = {'land.geojson': 'Land polygons assembled from OSM natural=coastline (sea is everything else); ~3 m simplification',
                         'water.geojson': 'Lakes, reservoirs and river areas over ~20 m x 20 m'}
    region = json.dumps(meta, indent=2, ensure_ascii=False).encode('utf-8')
    target = out / (pid + '.zip'); part = target.with_suffix('.zip.part')
    with zipfile.ZipFile(part, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr('region.json', region)
        for n, p in files.items(): z.write(p, n)
    part.replace(target)
    index.append({'id': pid, 'display_name': meta['display_name'], 'areas': meta['areas'], 'bounds': meta['bounds'], 'file': target.name,
                  'bytes': target.stat().st_size, 'sha256': sha256(target), 'unpacked_bytes': len(region) + sum(p.stat().st_size for p in files.values()), 'built': meta['built']})
    print(f"{pid}: {target.stat().st_size:,} bytes zipped, roads.geojson {files['roads.geojson'].stat().st_size:,}", flush=True)
(out / 'packs-index.json').write_text(json.dumps({'version': 1, 'built': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'packs': index}, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
