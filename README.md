# Daanvia

Offline motorcycle navigation for Android that projects its own navigation scene to a QJ Motor / ATR 160 dashboard over Wi-Fi Direct. Not certified for road use.

Download `daanvia-<version>.apk` from the [latest release](https://github.com/danrave1234/daanvia/releases/latest). The one APK runs on any Android 10+ phone. Later versions update from inside the app (Settings › About).

## What it does

- **Maps you choose to download.** Frame an area on the map, see its size in km and MB, and tap Download. Areas cover the whole Philippines and work without signal afterwards. Manage them in Settings › Maps.
- **Like Google Maps with signal.** Zoomed in, the map shows buildings, shops, food and services by name, street names and neighbourhoods from OpenFreeMap's OpenStreetMap tiles. Search also asks Photon (OpenStreetMap search) for every branch and street in the country. What was seen online is kept on the phone (up to 200 MB of map, plus found places), so it still shows without signal.
- **Finds you like Google Maps**: last known spot at once, then Wi-Fi, cell and GPS fixes with a circle showing how sure it is. Directions use high-accuracy GPS.
- **Offline routing and rerouting** with Valhalla Mobile (scooter costing), turn-by-turn voice, and a follow camera.
- **Search** places, towns and the parking lots inside them (for example SM City Cebu › its parking), with opening hours and a live check that a lot still exists on OpenStreetMap. A place and its area work too: "chowking guadalupe".
- **Saved places**, including places shared from Google Maps or opened from `geo:` and Maps links.
- **Dashboard projection**: a landscape, Carbit-style scene rendered on the phone and streamed with the hardware H.264 encoder over the PXC transport. It connects on its own when the dashboard shows its QR screen. Fit, text size and safe insets are saved per bike in Settings › Bike display.
- **Day and night themes** that follow the sun at the rider's position, with a manual override.
- **Keeps itself tidy**: unfinished downloads, installed update files and routing copies for removed areas are cleaned up on launch. Settings › Maps shows storage use and has Free up space.

## Build

JDK 21, Android SDK platform 36:

    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

Gradle 8.13 and Android Gradle Plugin 8.13.2. Min SDK 29, target SDK 35. The release build is one APK for every Android phone (64-bit and 32-bit ARM). Debug builds also include x86_64 for testing on a PC emulator. No API keys are needed.

Debug builds use `signing/prototype.p12`, a public test key, and can update other debug builds. Releases require the private signing configuration in `~/.daanvia/signing.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); give `storeFile` as an absolute path, and keep that file and keystore outside the repository. With that configuration installed, run `./gradlew :app:assembleRelease`. A debug APK cannot update a release signed with the private key.

The debug CI check runs unit tests, Android lint and APK assembly with JDK 21. It does not need release signing credentials.

## Free beta improvements

- Route previews offer distinct alternatives when the offline router finds them. Tap a route card or line; the selected route supplies directions and the bike display. Scooter access and one-way restrictions remain enabled.
- The original Settings, Support and bike-display editor layouts are preserved. The proposed Settings reorganization and global control resizing were reverted after review.
- Short offline commands, opened from the ride notification's Commands button, can repeat guidance, report remaining time/distance, and mute guidance or end a ride, each after a confirmation. Listening requires Android 12+ and an installed on-device English recognizer; typing a command works on all supported Android versions. There is no cloud fallback.
- Trip preparation supports ordered stops, locally saved trips and GPX route/waypoint import and export. Compatible areas from the same map generation can share routing tiles. Missing detailed map coverage is reported.
- Guidance uses staged prompts and audio focus. Local ride diagnostics record GPS freshness, connection state, battery and temperature without route coordinates. Emulator results do not establish physical-bike, helmet-audio or battery endurance performance.

Verification details and remaining hardware checks are tracked in `docs/improvement-progress.md`.

## Releases

App releases (`vX.Y.Z`) carry only the app: one `daanvia-X.Y.Z.apk`. The app checks `releases/latest` for updates.

The downloadable map areas are published as map generations, one per build:

- `maps-<generation>` (e.g. `maps-g20261101T000000Z`): a prerelease that is never changed once uploaded, with the generation's `cell_*-<sha>.zip` and `base_*-<sha>.zip` files (map, search, parking and routing data, named after their sha256) and its `areas-index.json` (generation id, OSM data time, build time, Valhalla and package-schema versions, every file with its size and sha256).
- `maps` / `areas-current.json`: the live generation's index. The app reads it from `releases/download/maps/` and downloads the files from the release it names.
- `maps` / `areas-index.json` and its zips: the last build before generations. App versions up to 0.8.0 read only this file, so it stays as it is until nobody runs them.

Map releases are never marked latest (`--prerelease --latest=false`), so the app's update check and the website only see app releases. One release holds at most 1000 files and a generation is about 560, which is why each generation gets its own. Publishing uploads the whole new generation first and switches `areas-current.json` last; the previous generation's release stays until the next cleanup, so a phone in the middle of a download finishes it.

To rebuild the areas from a fresh Geofabrik extract, run `tools/build_grid.py`, then `tools/split_grid.py --towns app/src/main/assets/overview/towns.json`, then `tools/verify_grid.py`. Output goes to `dist/areas/`. Then publish with `tools/publish_maps.py dist/areas`: `plan` (changes nothing), `upload`, `switch`, then `cleanup --yes`. `python tools/test_publish_maps.py` tests the publishing steps on a local folder. Sizes and coverage notes are in `docs/offline-coverage.md`.

## Data and licenses

Map data © OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright). Source: https://download.geofabrik.de/asia/philippines-latest.osm.pbf. Derived roads, search, parking and routing data keep their ODbL obligations.

MapLibre Native: BSD-2-Clause. Valhalla and Valhalla Mobile: MIT. ZXing: Apache-2.0. Figtree: OFL 1.1. Notices are in `app/src/main/assets/licenses/` and in the app under Settings › About.
