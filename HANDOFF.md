# Daanvia handoff

The owner rejected the current phone UI/UX. Treat the current interface as an engineering prototype, not an approved design. Redesign it before adding more product features; preserve and regression-test the transport separately.

## Owner requirements

- Native Android navigation with an independent TFT scene, not portrait phone-screen mirroring.
- Offline-first maps, search, routing and rerouting; prefer engineering investment over recurring per-trip mapping charges. Initial package is Metro Cebu only.
- Phone setup and destination selection must be straightforward. The TFT is primarily a passive, glanceable navigation display; do not require tapping it.
- As the destination approaches, automatically show nearby parking pins on the TFT. Put known motorcycle parking rates on the pins only. If a price is unknown, omit the price entirely. No parking footer, selection tray or details panel.
- Adapt to different TFT sizes and native bike overlays using editable safe insets, text sizing and saved per-bike fit settings. Avoid stretching the phone image. Keep legal attribution visible.
- Optional connected features later: fresh traffic, event/festival closures, toll information, parking and AI trip planning. Do not invent rates, availability or road restrictions. AI should add evidence-backed trip usefulness, not sit in the critical GPS/routing loop.
- Keep implementation simple and complete. Obtain visual approval of a coherent UI design and verify the actual implementation against it.

## Current implementation and evidence

Java Android app; MapLibre Native phone map; Valhalla Mobile local scooter routing; bundled OSM-derived roads/search/parking and routing graph. Dedicated Canvas/OpenGL/H.264 TFT scene uses Wi-Fi Direct and the independently exercised PXC transport. The earlier prototype visibly projected phone video on the owner's ATR 160; that observation does not validate the new native navigation build.

Build: JDK 21, Gradle 8.13, AGP 8.13.2, compile SDK 36, target 35, min 29. See README.md for exact commands, features, setup and licenses. No map API key required. Signing key is an explicitly public prototype TEST key; it exists only to update earlier test installations, not for production signing.

11 unit test cases passed; lint reports zero errors and 17 warnings. Host Valhalla checks passed for a forward, moved-origin and reverse route, plus outside-region rejection. See verification/. These do not prove Android native execution, correct visual output, battery use or on-bike behavior. No physical phone/emulator verification was performed during this build.

## Known unfinished work

- Phone UI is rejected and needs a substantive redesign. Current map is a minimal road geometry view, not a complete cartographic product.
- New native routing/runtime, memory, foreground location, locked-screen projection, fit calibration and thermal behavior require device measurement.
- Dashboard reconnect is manual; automatic recovery remains unfinished.
- Live traffic, event closures, parking occupancy, operator-verified fee feeds and AI features are not implemented.
- Bundled OSM parking has no verified prices; optional owner-entered rates expire visually after 30 days. Parking records do not guarantee motorcycle acceptance or space availability.
- Review complete dependency licensing, Android target behavior, privacy and independent-product positioning before commercialization.

Start with a parked device test using verification/DEVICE-TEST.txt and a UI redesign. Preserve the working transport while iterating the app experience.
