# Philippines-wide offline coverage: measurements and recommendation

Measured 19 Oct 2026 against Geofabrik `philippines-261018.osm.pbf` (611 MB, MD5 verified) with Valhalla 3.9.0 (pyvalhalla) and the app's own `valhalla-template.json`. "Compressed" is deflate-6, which is what an APK/AAB zip entry costs. Pack areas are in `tools/packs.json`.

## Sizes (MB, raw / compressed)

| Pack | Routing graph | Roads + search + parking (GeoJSON/JSON) | Land + water polygons | Total raw | Compressed |
|---|---|---|---|---|---|
| Metro Cebu (ships today) | 19.4 / 7.3 | 17.8 / 3.2 | 0.5 / 0.1 | 38 | **11** |
| Metro Manila | 105.0 / 37.6 | 81.6 / 14.0 | 2.9 / 0.7 | 189 | **52** |
| Visayas | 119.3 / 48.1 | 128.1 / 27.2 | 23.4 / 5.7 | 271 | **81** |
| Mindanao | 161.9 / 67.6 | 197.4 / 45.2 | 20.3 / 5.4 | 380 | **118** |
| Luzon (incl. Palawan, Mindoro, Masbate) | 344.2 / 132.7 | 339.8 / 68.7 | 36.5 / 9.2 | 721 | **211** |
| Philippines, one graph | 598.8 / 237.3 | 632.9 / 134.1 | 73.8 / 18.7 | 1,306 | **390** |

- The per-pack graphs come from clipped PBFs (`tools/clip_pack_pbf.py`). Carving the country graph by area instead keeps whole coarse tiles, so it costs more for city packs: 154 MB for Metro Manila and 31 MB for Cebu.
- Roads alone are 597 MB of GeoJSON nationwide, with 1.62 M ways and 554 k place names. Footways, paths and steps make up 70 MB of that.
- Parks and forest polygons (`green.geojson`) are not in the table. They would add 145 MB raw / 36 MB compressed nationwide, so they are not worth shipping in this format.
- Build cost on this PC:
  - Nationwide graph: 2.5 min with 3.8 GB peak RAM.
  - Data pass: 13 min.
  - Clip: 7 min.
  - Per-pack graphs: 4–68 s.
- Today's debug APK is 68.9 MB: 42 MB of native libraries (two ABIs) plus 12 MB of Cebu data.

## Can a single APK hold it?

**No.**

- **Size.** A nationwide APK would be about 450 MB to download. On the phone it would take about 1 GB, because the graph tar is also copied out of the APK into app storage. That is far above Google Play's base download limit.
- **Memory.** This is the real blocker. `NavSession` reads `roads.geojson` whole into a String, parses every road into memory, and hands the same String to MapLibre. Luzon (317 MB), Visayas (121 MB) and Mindanao (190 MB) cannot fit a normal Android heap, and Metro Manila (72 MB) is already risky.
- **Graph.** The routing graph scales fine: Valhalla memory-maps the tar.

Island packs therefore need the map/search format change below before they can ship. City packs of Cebu's size work with the current format.

## Recommended pack format

One directory per pack, named by its id.

- **`region.json`** carries the pack's metadata:
  - `id`, `display_name`, `bounds`
  - optional `areas`: several `[minLon,minLat,maxLon,maxLat]` boxes, so an archipelago pack does not claim the sea between groups
  - `home`, `demo`, `built`, source and licence
  - `files` with bytes + sha256 for each file
- **`valhalla_tiles.tar`** + **`valhalla-template.json`** stay unchanged.
- **Map:** replace `roads.geojson` with a **PMTiles vector archive** of roads, land, water and labels. MapLibre Android ≥ 11.7 reads `pmtiles://file://…` from app storage, and the app is on 13.7.1. The TFT renderer should then query a small per-pack spatial index (or the same tiles) instead of holding every road.
- **Search:** replace `places.json` with a per-pack SQLite FTS file.
- **Pack layout:** three island-group packs plus Metro Manila and Metro Cebu city packs.
  - Packs may overlap. The app routes inside the smallest installed pack that holds both ends.
  - Inter-island trips fail across separate packs. With the nationwide graph, Cebu→Davao routes over a RoRo ferry (675 km).
  - Shipping the nationwide graph (237 MB compressed) as its own optional pack enables those trips. The template's `max_distance` of 500 km for `motor_scooter` still rejects Manila→Cebu, so raise it if long trips matter.

## How the phone gets packs (offline-first, no per-trip fees)

1. **Bundled:** keep one small starter city pack in the APK (Metro Cebu today, 11 MB).
2. **Downloaded once (recommended now):** the app is sideloaded with a test key, so host the pack zips on a static file host.
   - Download over Wi-Fi, check the sha256 from `region.json`, and unpack to `files/regions/<id>/`.
   - The download is a one-time fetch per pack refresh (rebuild monthly from Geofabrik). After that everything runs offline.
3. **Play Asset Delivery** once the app is on Play: one on-demand pack per region.
   - Engine docs quote 512 MB per on-demand pack. That fits Luzon at 211 MB compressed, but check Google's current limits first.
   - Copy or point the delivered folder into `files/regions/<id>/`.
4. **User-supplied:** copy an unpacked pack over USB to `Android/data/net.drave.atrdiagnostics/files/regions/<id>/`. This works today and is useful for field tests.

## What is implemented now (prototype)

- **`NavSession` discovers every installed pack**, in this order:
  1. `files/regions/<id>/`
  2. `Android/data/…/files/regions/<id>/`
  3. `assets/regions/<id>/`
  4. the legacy `assets/cebu/`

  The first copy of an id wins.
- **Startup pack:** it shows the pack containing the last known location, else the one shown last time, else the first.
- **`plan()`:**
  - Picks the smallest pack containing both origin and destination, and routes with that pack's own Valhalla engine. Engines are created lazily and cached per pack.
  - If that pack is not the one shown, it swaps in its map, search and parking data first.
  - Cross-pack trips are rejected with "Start and destination are in different offline regions".
  - Reroute reuses the planned route's pack.
  - The routing request, the reroute rule (3 off-route fixes, 12 s gap) and GPS handling are unchanged.
- **The bundled graph is re-copied when its size in `region.json` changes.** Before, the first install's copy was kept forever, so an APK update could never replace a stale graph.
- **The support report now includes `offline_region` and `installed_regions`.**
- **`regionText("lots.json")`** (or any other pack file) reads from the pack now shown, so the UI does not need asset paths. Call it off the UI thread and reload when `regionVersion` changes.
- **Why the bounds check matters:** host tests show that ferry lines let a pack's graph "route" far outside the pack. The Visayas pack returned a 2,269 km ferry route from Cebu to Davao. The app-level check stops this before Valhalla is called.
- **Tests:** `RegionPackTest` has 8 cases. The build, 39 unit tests and lint pass (0 errors). Not run on a phone or emulator.
- **Host route checks (pyvalhalla 3.9.0, the app's scooter request):** every pack's demo route works.

  | Pack / trip | Distance | Uses a ferry |
  |---|---|---|
  | Cebu demo | 3.96 km | no |
  | Manila demo | 8.3 km | no |
  | Luzon demo | 13.7 km | no |
  | Davao demo | 6.6 km | no |
  | Manila→Baguio (Luzon pack) | 268 km | no |
  | Cebu→Iloilo (Visayas pack) | 217 km | yes |

  Route times were 60–650 ms on the host.

## Coastlines and land use

- **Coastlines:** `tools/build_packs.py` assembles OSM `natural=coastline` into land polygons. Nationwide that gives 7,915 islands, all closed, with no open chains. They are clipped per pack and simplified to about 3 m.
- **Water:** lakes, reservoirs and river areas over about 20 m × 20 m go into `water.geojson`.
- **Cost:** land plus water is cheap (Cebu 0.5 MB, nationwide 74 MB raw). Parks and forest are not cheap (see above).
- **Shipped:** `land.geojson` (170 KB) and `water.geojson` (301 KB) are now in `assets/cebu/` and listed in `region.json`.
- **Not drawn yet.** `MapStyle` needs:
  - a sea-coloured background
  - a fill layer for `land.geojson`, then one for `water.geojson`
  - roads on top

## Tools

`tools/packs.json` defines the packs. The pipeline:

1. `tools/build_packs.py` makes the per-pack map, search, parking, land and water data in one pass. It also writes the search-only `lots.json` in `tools/build_lots.py`'s record shape; for Metro Cebu the output is identical to `build_lots.py`. It runs on Windows Python with `osmium` and `shapely`.
2. `tools/clip_pack_pbf.py` makes one routing input per pack.
3. `tools/build_pack_tiles.py --clipped DIR` builds a graph per pack; `--country PBF` builds the country graph and carves it.
   - It needs Linux/WSL with `pyvalhalla==3.9.0` and `shapely`.
   - The work directory must be on the Linux filesystem: `valhalla_build_tiles` crashes on `/mnt/<drive>`.
