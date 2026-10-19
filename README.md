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

    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug

Gradle 8.13 and Android Gradle Plugin 8.13.2. Min SDK 29, target SDK 35. The release build is one APK for every Android phone (64-bit and 32-bit ARM). Debug builds also include x86_64 for testing on a PC emulator. No API keys are needed.

Both build types are signed with `signing/prototype.p12`, a public prototype TEST key, so new builds install over earlier test builds. It must never become the production signing key.

## Releases

App releases (`vX.Y.Z`) carry only the app: one `daanvia-X.Y.Z.apk`. The app checks `releases/latest` for updates.

The downloadable map areas live in a separate release tagged `maps`, which the app reads from `releases/download/maps/`:

- `areas-index.json`: the catalog of area cells with sizes and sha256
- `cell_*.zip` and `base_*.zip`: map, search, parking and routing data per cell

Publish the `maps` release with `--latest=false` so it never becomes the latest app release. Replacing its files updates maps for everyone without an app update.

To rebuild the areas from a fresh Geofabrik extract, run `tools/build_grid.py`, then `tools/split_grid.py --towns app/src/main/assets/overview/towns.json`, then `tools/verify_grid.py`. Output goes to `dist/areas/`. Sizes and coverage notes are in `docs/offline-coverage.md`.

## Data and licenses

Map data © OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright). Source: https://download.geofabrik.de/asia/philippines-latest.osm.pbf. Derived roads, search, parking and routing data keep their ODbL obligations.

MapLibre Native: BSD-2-Clause. Valhalla and Valhalla Mobile: MIT. ZXing: Apache-2.0. Figtree: OFL 1.1. Notices are in `app/src/main/assets/licenses/` and in the app under Settings › About.
