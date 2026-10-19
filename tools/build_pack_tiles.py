"""Build the Valhalla 3.9.0 routing graph (valhalla_tiles.tar) for each pack in tools/packs.json.

usage (Linux/WSL with `pip install pyvalhalla==3.9.0 shapely`):
  python tools/build_pack_tiles.py WORKDIR PACKDIR --clipped CLIPDIR [--only id,id]   # one graph per clipped pack PBF
  python tools/build_pack_tiles.py WORKDIR PACKDIR --country philippines-latest.osm.pbf [--only ph-all]   # country graph, carved per pack

--clipped expects CLIPDIR/<pack id>.osm.pbf from tools/clip_pack_pbf.py; the graph stops at the pack edge
(Metro Manila 105 MB). --country builds one graph and keeps, per pack, every tile touching its areas; whole
level-0/1 tiles come along, so city packs grow (Metro Manila 154 MB) but ph-all is the full country graph.
WORKDIR must be on a native Linux filesystem: valhalla_build_tiles memory-maps its intermediate files and
crashes on a WSL /mnt drive. PACKDIR receives <pack id>/valhalla_tiles.tar and valhalla-template.json.
"""
import argparse, json, pathlib, shutil, subprocess, sys
import valhalla

ap = argparse.ArgumentParser()
ap.add_argument('work'); ap.add_argument('packdir')
ap.add_argument('--clipped'); ap.add_argument('--country')
ap.add_argument('--packs', default=str(pathlib.Path(__file__).with_name('packs.json')))
ap.add_argument('--only', default='')
args = ap.parse_args()
if bool(args.clipped) == bool(args.country): ap.error('pass exactly one of --clipped or --country')
root = pathlib.Path(__file__).resolve().parents[1]; template_file = root / 'app/src/main/assets/cebu/valhalla-template.json'
work = pathlib.Path(args.work).resolve(); work.mkdir(parents=True, exist_ok=True)
packdir = pathlib.Path(args.packdir).resolve(); pkg = pathlib.Path(valhalla.__file__).parent
packs = json.loads(pathlib.Path(args.packs).read_text())['packs']
if args.only: packs = [p for p in packs if p['id'] in args.only.split(',')]

def config(tiles):
    cfg = json.loads(template_file.read_text()); m = cfg['mjolnir']
    m.update(tile_dir=str(tiles), tile_extract=str(tiles) + '.tar', concurrency=4); m.pop('tile_url', None)
    for k in ('traffic_extract', 'admin', 'landmarks', 'timezone', 'transit_dir', 'transit_feeds_dir'): m[k] = ''
    path = tiles.parent / (tiles.name + '.json'); path.write_text(json.dumps(cfg, indent=1)); return path
def build(pbf, tiles):
    shutil.rmtree(tiles, ignore_errors=True); cfg = config(tiles)
    subprocess.check_call([str(pkg / 'bin' / 'valhalla_build_tiles'), '-c', str(cfg), str(pathlib.Path(pbf).resolve())], stdout=subprocess.DEVNULL)
    return cfg
def extract(cfg, tiles, dest, shapes=None):
    inline = json.dumps({'mjolnir': {'tile_dir': str(tiles), 'tile_extract': str(dest / 'valhalla_tiles.tar')}})
    subprocess.check_call([sys.executable, str(pkg / 'valhalla_build_extract.py'), '-c', str(cfg), '-i', inline, '-O'] + (['-g', str(shapes)] if shapes else []))

if args.country:
    tiles = work / 'country'; cfg = build(args.country, tiles)
for spec in packs:
    dest = packdir / spec['id']; dest.mkdir(parents=True, exist_ok=True)
    if args.clipped:
        tiles = work / spec['id']; cfg = build(pathlib.Path(args.clipped) / (spec['id'] + '.osm.pbf'), tiles); extract(cfg, tiles, dest); shutil.rmtree(tiles)
    else:
        shapes = work / 'areas' / spec['id']; shutil.rmtree(shapes, ignore_errors=True); shapes.mkdir(parents=True)
        boxes = [[[a[0], a[1]], [a[2], a[1]], [a[2], a[3]], [a[0], a[3]], [a[0], a[1]]] for a in spec['areas']]
        (shapes / 'areas.geojson').write_text(json.dumps({'type': 'FeatureCollection', 'features': [{'type': 'Feature', 'properties': {}, 'geometry': {'type': 'Polygon', 'coordinates': [b]}} for b in boxes]}))
        extract(cfg, tiles, dest, shapes)
    # The app rewrites tile_extract/tile_dir on the phone; ship the same template as the Cebu pack.
    shutil.copyfile(template_file, dest / 'valhalla-template.json')
    print(spec['id'], (dest / 'valhalla_tiles.tar').stat().st_size, 'bytes', flush=True)
