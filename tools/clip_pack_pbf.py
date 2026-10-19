"""Clip routing input per pack from the country PBF in one pass.

usage: python tools/clip_pack_pbf.py philippines-latest.osm.pbf OUTDIR [--packs tools/packs.json] [--only id,id]

Writes OUTDIR/<pack id>.osm.pbf with every highway/ferry way that has a node inside one of the pack's areas,
the nodes those ways use (tags kept, so barriers and signals survive) and turn restrictions on kept ways.
Build each with valhalla_build_tiles for a graph that stops at the pack edge; carving the country graph
instead (tools/build_pack_tiles.py) keeps whole level-0/1 tiles and is larger for city packs.
"""
import argparse, json, pathlib, time
import osmium

ap = argparse.ArgumentParser()
ap.add_argument('pbf'); ap.add_argument('out')
ap.add_argument('--packs', default=str(pathlib.Path(__file__).with_name('packs.json')))
ap.add_argument('--only', default='')
args = ap.parse_args()
out = pathlib.Path(args.out); out.mkdir(parents=True, exist_ok=True)
packs = json.loads(pathlib.Path(args.packs).read_text())['packs']
if args.only: packs = [p for p in packs if p['id'] in args.only.split(',')]
writers = {p['id']: osmium.BackReferenceWriter(str(out / (p['id'] + '.osm.pbf')), ref_src=args.pbf, overwrite=True, remove_tags=False) for p in packs}
kept = {p['id']: set() for p in packs}
def inside(areas, lon, lat): return any(a[0] <= lon <= a[2] and a[1] <= lat <= a[3] for a in areas)
fp = (osmium.FileProcessor(args.pbf).with_locations('sparse_file_array,' + str(out / 'nodes.idx'))
      .with_filter(osmium.filter.KeyFilter('highway', 'route', 'type')))
started = time.time()
for o in fp:
    if o.is_way():
        t = o.tags
        if 'highway' not in t and t.get('route') != 'ferry': continue
        try: pts = [(n.location.lon, n.location.lat) for n in o.nodes]
        except osmium.InvalidLocationError: continue
        for p in packs:
            if any(inside(p['areas'], x, y) for x, y in pts): writers[p['id']].add_way(o); kept[p['id']].add(o.id)
    elif o.is_relation() and o.tags.get('type') == 'restriction':
        for p in packs:
            if all(m.type != 'w' or m.ref in kept[p['id']] for m in o.members) and any(m.type == 'w' for m in o.members): writers[p['id']].add_relation(o)
print(f'Scan {time.time()-started:.0f}s; writing back-references…', flush=True)
for pid, w in writers.items():
    w.close(); print(pid, len(kept[pid]), 'ways', (out / (pid + '.osm.pbf')).stat().st_size, 'bytes', flush=True)
