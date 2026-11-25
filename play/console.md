# Google Play Console: what to enter

Answers for Daanvia's Play Console forms, matching what the Play build (`./gradlew bundlePlayRelease`) does.
Store listing text is in `listing/en-US/`, graphics in `graphics/`.

## Before the first upload

- **Account.** A personal developer account made after 13 Nov 2023 must run a closed test with at least 12 testers,
  opted in for 14 days in a row, before it can apply for production. Recruit 15 or more, in case some drop out.
- **App signing.** Choose to upload your own app signing key (Play Console › Setup › App signing › "Use a different
  key" › export with Google's PEPK tool) and give it the release key in `C:\Users\Danrave\.daanvia\release.p12`.
  Then the Play build and the website APK share one signature, so riders can move between them without losing maps,
  places or the bike pairing. If Play generates a new key instead, the two can never update each other.
- **Upload key.** Play asks for a separate upload key after that. Make a new one and keep it beside the release key
  (never in the repository); Gradle signs the bundle with whatever `signing.properties` names.

## Pricing

- Set up a payments profile (Play Console › Setup › Payments profile) before the price can be set.
- Set the price to **₱99** (Monetize › Products › App pricing) **before anything is published to any track**. A free
  app can never be made paid; a paid app can be made free later.
- One payment covers the app and every update. No in-app purchases, so no billing code in the app.
- Countries: start with the Philippines, then add others if riders ask.

## App details

| Field | Value |
| --- | --- |
| App name | Daanvia Offline Motorcycle GPS (`listing/en-US/title.txt`) |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | **Paid, ₱99** (see Pricing) |
| Category | Maps & Navigation |
| Website | https://daanvia-app.vercel.app |
| Privacy policy | https://daanvia-app.vercel.app/privacy |
| Email | a support address you read (shown publicly) |

## App content

- **Privacy policy:** the URL above.
- **App access:** "All functionality is available without special access." In the notes: *No account. The bike
  display needs a compatible motorcycle dashboard; without one, Settings › Bike display shows a live preview of what
  it would show, and Settings › Help › Demo ride plays a sample route.*
- **Ads:** No, the app doesn't contain ads.
- **Content rating:** category "All other app types". No violence, sexuality, language, controlled substances,
  gambling or user-generated content. Users can't talk to each other. The app doesn't share the user's location with
  other users. No digital purchases.
- **Target audience:** 18 and over. Not designed for children.
- **News app:** No. **Government app:** No. **Financial features:** none. **Health:** none.
- **Advertising ID:** No. The app doesn't use it (check the bundle's merged manifest has no `AD_ID` permission).

## Data safety

Data is "collected" when it leaves the phone. Everything else (precise location, routes, saved places, notifications
read for the bike, the camera for the QR code, voice commands, which run on-device) stays on the phone and isn't
declared.

| Data type | Collected | Shared | Optional | Purpose | Why |
| --- | --- | --- | --- | --- | --- |
| Approximate location | Yes | Yes | No | App functionality | A position rounded to about 100 m goes to Photon (online search) and OpenStreetMap's Overpass servers (fuel stops, parking checks), only with signal |
| In-app search history | Yes | Yes | No | App functionality | Words typed into search go to Photon when the phone has signal |
| Diagnostics (App info and performance) | Yes | No | Yes | Analytics | The connection and ride log, every few minutes; Settings › Help can turn it off; kept 30 days |
| Device or other IDs | Yes | No | Yes | Analytics | A random id made for this install, sent with the log |

- Data is encrypted in transit: **Yes** (HTTPS only; the app refuses plain HTTP).
- Users can ask for data to be deleted: **Yes**. Logs are deleted after 30 days, and riders can turn diagnostics off.
  Add the support email as the way to ask sooner.
- With signal, map tiles come from OpenFreeMap. The tile requests show which area is on screen but carry nothing the
  app adds, so they aren't declared above; the privacy page mentions them.

## Permissions and foreground services

The Play build has no install-packages, delete-packages or battery-exemption permissions. Location is foreground
only: no background-location form is needed.

**Foreground service types** (App content › Foreground service permissions). Each needs a description, the user
impact if Android delayed it, and a link to a short video (an unlisted YouTube link is fine).

| Type | Service | Description | Video should show |
| --- | --- | --- | --- |
| location | NavService | Turn-by-turn navigation the rider starts. It keeps following the rider's position and speaking directions with the screen off or the phone in a pocket. | Pick a destination, tap Start ride, lock the phone, and directions still speak |
| connectedDevice | StreamService | Sends the navigation screen to the motorcycle's dashboard over Wi-Fi Direct after the rider pairs it by scanning its QR code. | Scan the bike's QR, tap Connect bike, the directions appear on the dashboard |
| mediaProjection | StreamService (mirroring) | Shows the phone's own screen on the dashboard when the rider taps Mirror phone to bike and accepts Android's screen-sharing prompt. | Tap Mirror phone to bike, accept Android's prompt, the phone screen appears on the dashboard |

If you don't have the bike at hand to film, record the location video first and the bike ones on the next ride.

**Notification access** (optional, for cards on the bike). No form, but Play requires an in-app explanation before
the rider is sent to Android's Notification access screen; the app shows one.

## Testing tracks

1. **Internal testing** (free even for a paid app): upload the first bundle, install it from Play on your own phone,
   and check the update, the bike display and notification cards.
2. **Closed testing** (testers must buy a paid app here): 15 or more riders for 14 days, then apply for production.
   Give testers promo codes (Monetize › Promotions, up to 500 a quarter) so they don't pay; try one code with one
   tester first, since it isn't documented whether codes work inside a closed test. If they don't, testers buy for
   ₱99 and you refund them from Order management.
3. **Production.**

The website APK (`./gradlew assembleSiteRelease`, "Daanvia Beta") stays a private download for testers and for phones
without Play. It uses the same app id and key, so it installs over the Play version and back.
