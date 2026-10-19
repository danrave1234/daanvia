# Daanvia 0.4.0-alpha1

First native Android navigation development build for the independently tested ATR 160 projection transport. This is a functional engineering alpha, not a release certified for road use. The existing diagnostic project was retained separately.

## Included

- MapLibre Native phone map using bundled OpenStreetMap-derived road geometry. No map account or runtime tile service.
- Valhalla Mobile 0.6.4 native Android routing over a bundled graph built with Valhalla 3.9.0. Scooter costing, highway/toll avoidance preferences; these do not certify 160 cc legality.
- Offline place-name search, map-pin destinations, GPS foreground service, maneuver guidance, installed Android TTS voice and an explicit simulation mode.
- Recalculation after repeated accurate off-route fixes; local route progress and weak/stale GPS indication.
- Independent Canvas/OpenGL navigation rendering into the hardware H.264 encoder and the proven Wi-Fi Direct/PXC connection. No MediaProjection consent is needed for the navigation scene.
- TFT safe insets, text sizing, map/large-turn layouts, calibration grid and saved fit settings keyed to the saved bike.
- Passive parking icons near the destination. No parking footer, TFT selection controls or 'unknown price' labels.
- Optional owner-entered motorcycle parking rates saved on the phone; valid rates are attached to pins and suppressed after 30 days. OSM parking prices are deliberately not invented or bundled as verified.
- Navigation report sharing with location coordinates, destination names and pairing credentials excluded from the report. Pairing details stay in app-private storage.

## First test: parked

1. Install the APK as an update to ATR QR Connect. It uses the same application ID and prototype signing certificate; it appears as Daanvia. Scan the dashboard QR again if no saved bike is available.
2. Open Daanvia and wait for 'Metro Cebu offline'. First launch copies the routing graph into private storage and parses the region.
3. Tap **Demo route**, then **Load demo**. Simulation is explicitly labeled on the screen. Confirm a route and changing maneuvers on the phone without an Internet connection.
4. Open **Bike & fit > Connect / scan dashboard**. Close Carbit Ride, keep Wi-Fi on and hotspot off, scan the QR if needed, then tap **Send Daanvia to dashboard**. The app returns to navigation after starting the display session.
5. To adjust fit, **End ride** first. Open **Bike & fit > Fit my TFT**. Reconnect the display if necessary, then reserve the real bike footer with the bottom safe-area control. The calibration grid should show all edges and a round circle. Save for this bike.
6. Use **Preview dashboard** to inspect the composed scene on the phone. It is a scene preview, not a screenshot received from the TFT.
7. For a live-position check, end simulation, tap **My location**, grant precise location and wait for GPS. Choose a place or map destination. Review the calculated route, then tap **Start offline ride**. Use an installed offline English TTS voice if you want speech without data.
8. **End ride** stops GPS, voice and projection. The foreground notifications also provide Stop actions.

## Parking test

While no ride is active, tap a map location and choose **Save motorcycle parking here**. Enter a checked motorcycle rate and its basis (hour, entry or two hours), or leave it blank. These are owner-entered local records, not independently verified fees or live occupancy. Near a destination, applicable pins appear on the TFT; only recorded current prices get price labels. Private/permit-only OSM lots are excluded. General parking records do not certify motorcycle acceptance.

## Coverage and limitations

The initial package is bounded Metro Cebu: longitude 123.60-124.08, latitude 10.10-10.62. It is not the whole Cebu province or the Philippines. Destination results and map detail reflect the bundled OSM snapshot and may be incomplete. Outside-region requests are rejected. There are no traffic, festival/closure, live parking occupancy, cloud AI or complete tariff feeds in this build.

Compilation and host routing checks passed; Android native routing, foreground behavior, visual fit, thermal behavior and locked-screen projection require a real-phone/bike test. No Android emulator or physical phone was available here. The current recovery from a lost dashboard transport is an explicit reconnect from Bike & fit; fully automatic reconnect remains work for the reliability beta. Map detail, startup memory and search performance need device measurement. The phone and TFT use different renderers; this first build is not a pixel-perfect reproduction of the concept artwork.

## Build

Use JDK 21, Android SDK platform 36 and build-tools 35.0.0:

    ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

Gradle 8.13 / Android Gradle Plugin 8.13.2 are pinned for API 36 and Kotlin 2.3 dependency compatibility. Target SDK remains 35 to preserve the tested transport behavior; update the target and retest platform behavior before store release.

The `tools/gradle_local.py` runner describes this environment's local setup and is not needed for an ordinary Android Studio build. No API keys are needed. The source includes a TEST signing key only, to allow updates to the earlier prototype; it is public within this source bundle and must never become the production signing key.

## Data preparation / provenance

Source: https://download.geofabrik.de/asia/philippines-latest.osm.pbf (retrieved 19 October 2026), OpenStreetMap contributors, ODbL-1.0. The smaller extracted input is included at `data-source/cebu.osm.pbf`. Region assets include geometry, search, parking, graph, attribution, checksums and metadata.

`tools/build_region.py` creates a region from the country PBF with pyosmium. `tools/sort_osm.py` sorts the derived PBF before graph construction. Build routing tiles with Valhalla 3.9.0 and package them with its `valhalla_build_extract.py`. `tools/verify_region.py` checks the bundled data and calculates routes against the local tar using pyvalhalla 3.9.0. No tile URL is configured. The app cannot build a new region on the phone yet.

OSM data attribution: © OpenStreetMap contributors, https://www.openstreetmap.org/copyright . Database license: https://opendatacommons.org/licenses/odbl/1-0/ . Derived regional input/graph/geometry retain their applicable ODbL obligations. Included source data accompanies the derived database; do not copy commercial map content into it.

MapLibre Native: BSD-2-Clause. Valhalla and Valhalla Mobile: MIT. ZXing: Apache-2.0. Notices are in app assets/licenses; transitive AndroidX, Kotlin, Moshi, OkHttp/Okio and other dependencies retain their respective licenses. Recheck the complete dependency/license inventory before commercial distribution. No GPL/AGPL reference-repository code was imported during this implementation.
