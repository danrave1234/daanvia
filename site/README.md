# Daanvia website

Three parts, none of which needs an edit when a new app version is released:

| Folder | What it is | Hosted on |
| --- | --- | --- |
| `web/` | Next.js site: features, videos, install steps, latest version and release notes | Vercel (like paytsek.online) |
| `download-worker/` | Cloudflare Worker that streams the latest APK as a direct download | Cloudflare Workers (free plan) |
| `video/` | Remotion project that renders the promo and tutorial videos from real app recordings | Run locally |

## How the download works

The Download button links to the Worker. On each request the Worker asks GitHub for the latest release
(cached for 5 minutes, with a 7-day stale copy in case GitHub is down or rate-limited), picks the one
asset named `daanvia-X.Y.Z.apk`, and streams it back with `Content-Disposition: attachment`. Visitors
never see GitHub. Range requests pass through, so an interrupted download on mobile data can resume.
The `maps` release is never "latest" and has no such asset, so it is ignored.

Routes: `/` and `/daanvia.apk` (latest), `/daanvia-0.7.1.apk` (a specific version), `/latest.json`.

Why a Worker and not a Vercel function: streaming a 35 MB file through a Vercel function counts against
the Hobby plan's 10 GB of Fast Origin Transfer, which is about 280 downloads a month, and going over
pauses every project on the account. Cloudflare doesn't charge for Worker bandwidth.

The site reads the same GitHub release list (re-checked every 10 minutes) to show the version, size,
date and "What's new" from the release notes. `/download` on the site redirects to the Worker, for a
short link to share.

## Settings

`web/` environment variables (Vercel project settings):

- `NEXT_PUBLIC_DOWNLOAD_URL`: the Worker's URL, for example `https://daanvia-download.<subdomain>.workers.dev/`
- `NEXT_PUBLIC_SITE_URL`: the site's own address, used for social previews
- `GITHUB_TOKEN` (optional): a read-only token for a higher GitHub API limit

The Worker takes an optional `GITHUB_TOKEN` secret (`npx wrangler secret put GITHUB_TOKEN`).

## Run locally

    cd download-worker && npx wrangler dev --port 8787
    cd web && npm install && NEXT_PUBLIC_DOWNLOAD_URL=http://127.0.0.1:8787/ npm run dev

## Deploy

    cd download-worker && npx wrangler deploy
    cd web && vercel deploy --prod

The Vercel project's root directory is `site/web`.

## Videos

`video/public/` holds trimmed screen recordings and screenshots from the emulator (not committed;
they are large). `npm run render` renders `promo`, `tut-install`, `tut-area` and `tut-bike` to `video/out/`
and writes 720p copies and posters into `web/public/media/`. Edit captions in `video/src/Promo.tsx` and
`video/src/Tutorial.tsx`; preview with `npm run studio`.

Footage was recorded on the `medium_phone` emulator with the app's demo ride and simulated GPS
fixes along EDSA; the bike display picture is the app's own live preview from Settings › Bike display.
