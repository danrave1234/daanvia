"""Extract every OpenStreetMap parking lot in a region, with the details riders ask about, for place search.

Usage: python tools/build_lots.py data-source/cebu.osm.pbf app/src/main/assets/cebu/lots.json [minLon minLat maxLon maxLat]

Unlike parking.json (which feeds map and TFT pins and leaves out private lots), lots.json keeps private and
customer-only lots so search can show them under the place they belong to, clearly marked. Every value is
copied from OSM tags; nothing is guessed. Keys are short to keep the asset small:
  id   "n123" / "w123" (OSM element, used for the live check)   lat, lon
  name  OSM name or ""              kind "moto" for amenity=motorcycle_parking, else "car"
  moto  "yes" / "no" / "" from the motorcycle tag ("yes" implied for motorcycle_parking)
  acc   access tag ("" if absent)   hrs opening_hours   fee fee tag   chg charge tag
  cap   capacity   capm capacity:motorcycle   op operator   typ parking tag (surface, multi-storey, ...)
"""
import json, sys
import osmium

pbf, out = sys.argv[1], sys.argv[2]
box = [float(x) for x in sys.argv[3:7]] if len(sys.argv) >= 7 else None

def inside(lon, lat):
    return box is None or (box[0] <= lon <= box[2] and box[1] <= lat <= box[3])

def record(oid, lon, lat, t):
    moto = t.get('motorcycle', '')
    if t.get('amenity') == 'motorcycle_parking' and not moto: moto = 'yes'
    r = {'id': oid, 'lat': round(lat, 7), 'lon': round(lon, 7), 'name': t.get('name', ''),
         'kind': 'moto' if t.get('amenity') == 'motorcycle_parking' else 'car', 'moto': moto,
         'acc': t.get('access', ''), 'hrs': t.get('opening_hours', ''), 'fee': t.get('fee', ''), 'chg': t.get('charge', ''),
         'cap': t.get('capacity', ''), 'capm': t.get('capacity:motorcycle', ''), 'op': t.get('operator', ''), 'typ': t.get('parking', '')}
    return {k: v for k, v in r.items() if v != ''}

lots = []
fp = osmium.FileProcessor(pbf).with_locations().with_filter(osmium.filter.KeyFilter('amenity'))
for o in fp:
    t = dict(o.tags)
    if t.get('amenity') not in ('parking', 'motorcycle_parking'): continue
    try:
        if o.is_node():
            lon, lat = o.location.lon, o.location.lat; oid = 'n%d' % o.id
        elif o.is_way():
            pts = [(n.location.lon, n.location.lat) for n in o.nodes if n.location.valid()]
            if not pts: continue
            lon, lat = sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts); oid = 'w%d' % o.id
        else: continue
    except osmium.InvalidLocationError: continue
    if inside(lon, lat): lots.append(record(oid, lon, lat, t))

with open(out, 'w', encoding='utf-8') as f: json.dump(lots, f, separators=(',', ':'), ensure_ascii=False)
named = sum(1 for x in lots if 'name' in x); hours = sum(1 for x in lots if 'hrs' in x)
print(f'{len(lots)} lots ({named} named, {hours} with opening hours) -> {out}')
