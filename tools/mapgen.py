"""Shared pieces of the map build (build_grid.py, split_grid.py, publish_maps.py): how roads are cut at cell edges,
how cell files are zipped and named, and what a map generation records about itself.

A map generation is one complete build, published in its own release by tools/publish_maps.py. Every file is named
after its own contents (cell_123.50_10.00-<first 12 hex of sha256>.zip), so a name never changes meaning, and
identical contents always zip to identical bytes. areas-index.json records the generation id, the OSM data time,
the build time, the Valhalla version (read from the tiles) and PACKAGE_SCHEMA.
"""
import datetime, hashlib, pathlib, zipfile
from shapely import clip_by_rect
from shapely.geometry import LineString

# What a cell zip holds. 2: grid cells, each road whole in the cell of its first point.
# 3: roads cut at the cell box and carried EDGE past it; files named after their sha256.
# 4: roads also carry bridge, tunnel (1 when set) and layer (when not 0), so the map can draw overpasses and
#    underpasses. Older apps ignore the extra properties.
PACKAGE_SCHEMA = 4
# Each cell's roads run this far past its box (~110 m), so a road leaving a downloaded cell for one that is not
# downloaded does not stop exactly at the line. Neighbouring cells therefore share a ~220 m band of road pieces.
EDGE = 0.001


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for blk in iter(lambda: f.read(1 << 20), b''): h.update(blk)
    return h.hexdigest()


def zput(z, name, data):
    """Add a file with fixed metadata, so the same contents always make the same zip bytes (and the same name)."""
    zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0)); zi.create_system = 3; zi.external_attr = 0o644 << 16
    zi.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(zi, data.encode('utf-8') if isinstance(data, str) else data, compresslevel=9)


def write_zip(out_dir, stem, entries):
    """Write entries [(name, str|bytes)] to <stem>-<sha12>.zip in out_dir; returns (path, sha256)."""
    part = pathlib.Path(out_dir) / (stem + '.zip.part')
    with zipfile.ZipFile(part, 'w') as z:
        for name, data in entries: zput(z, name, data)
    digest = sha256(part); target = part.with_name('%s-%s.zip' % (stem, digest[:12])); part.replace(target)
    return target, digest


def lines(g):
    """The LineStrings of length > 0 in a clip result (which may be empty, a line, a multi-line or a collection)."""
    return [p for p in getattr(g, 'geoms', [g]) if p.geom_type == 'LineString' and not p.is_empty and p.length > 0]


def clip(line, box, edge=0.0):
    """Pieces of a LineString inside box [minLon, minLat, maxLon, maxLat] grown by edge."""
    return lines(clip_by_rect(line, box[0] - edge, box[1] - edge, box[2] + edge, box[3] + edge))


def coords(piece, digits=6):
    """Rounded coordinates without the repeats rounding can make; None if fewer than two points are left."""
    out = []
    for x, y in piece.coords:
        q = [round(x, digits), round(y, digits)]
        if not out or q != out[-1]: out.append(q)
    return out if len(out) >= 2 else None


def middle(pieces):
    """A point halfway along the longest piece, (lon, lat)."""
    p = max(pieces, key=lambda g: g.length).interpolate(0.5, normalized=True); return p.x, p.y


def line(pts): return LineString(pts)


def engine_version(tile):
    """The Valhalla version a graph tile was built with (GraphTileHeader: 16 bytes at offset 16)."""
    with open(tile, 'rb') as f: f.seek(16); raw = f.read(16)
    return raw.split(b'\0', 1)[0].decode('ascii', 'replace')


def osm_timestamp(pbf):
    """Replication timestamp from the PBF header (Geofabrik sets it), else ''."""
    try:
        import osmium
        return osmium.io.Reader(str(pbf), osmium.osm.osm_entity_bits.NOTHING).header().get('osmosis_replication_timestamp', '')
    except Exception:
        return ''


def generation_id(built):
    """g + build time in UTC to the second, e.g. g20261022T041237Z: sortable and unique per build."""
    return 'g' + built.astimezone(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
