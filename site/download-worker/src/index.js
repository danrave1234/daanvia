// Daanvia APK download.
//
// Streams the APK from the latest GitHub release so visitors download it straight
// from this address, never from a GitHub page. Nothing here changes per release:
// it asks GitHub which release is latest (cached for 5 minutes) and picks the one
// asset named daanvia-X.Y.Z.apk. The `maps` release is never marked latest, and it
// has no such asset anyway.
//
// Routes
//   GET /                      latest APK (attachment)
//   GET /daanvia.apk           same
//   GET /daanvia-X.Y.Z.apk     that version's APK
//   GET /latest.json           { version, name, size, publishedAt, releaseUrl, notes }
//
// Range requests pass through, so interrupted downloads on mobile data can resume.

const REPO = 'danrave1234/daanvia';
const APK = /^daanvia-(\d+\.\d+\.\d+)\.apk$/;
const FRESH_SECONDS = 300;
const STALE_SECONDS = 7 * 24 * 3600;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors() });
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
    }

    try {
      if (url.pathname === '/latest.json') {
        const info = await latest(env, ctx);
        return Response.json(publicInfo(info), {
          headers: { ...cors(), 'Cache-Control': `public, max-age=${FRESH_SECONDS}` },
        });
      }

      if (url.pathname === '/' || url.pathname === '/daanvia.apk' || url.pathname === '/download') {
        return stream(request, await latest(env, ctx));
      }

      const pinned = url.pathname.slice(1).match(APK);
      if (pinned) return stream(request, await byTag(env, ctx, `v${pinned[1]}`));

      if (url.pathname === '/robots.txt') return new Response('User-agent: *\nDisallow: /\n');
      return new Response('Not found', { status: 404 });
    } catch (err) {
      console.error(err && err.stack ? err.stack : String(err));
      return new Response('The download is unavailable right now. Please try again in a minute.', {
        status: 503,
        headers: { 'Retry-After': '60', 'Cache-Control': 'no-store' },
      });
    }
  },
};

async function stream(request, info) {
  const headers = new Headers({ 'User-Agent': 'daanvia-download' });
  const range = request.headers.get('Range');
  if (range) headers.set('Range', range);

  const upstream = await fetch(info.url, { method: request.method, headers, redirect: 'follow' });
  if (!upstream.ok && upstream.status !== 206 && upstream.status !== 416) {
    throw new Error(`asset fetch ${upstream.status} for ${info.url}`);
  }

  const out = new Headers({
    'Content-Type': 'application/vnd.android.package-archive',
    'Content-Disposition': `attachment; filename="${info.name}"`,
    'Accept-Ranges': 'bytes',
    // The bytes behind "/" change with each release, so browsers and caches must not keep them.
    'Cache-Control': 'no-store',
    'X-Daanvia-Version': info.version,
    'X-Content-Type-Options': 'nosniff',
  });
  for (const h of ['Content-Length', 'Content-Range', 'ETag', 'Last-Modified']) {
    const v = upstream.headers.get(h);
    if (v) out.set(h, v);
  }
  return new Response(request.method === 'HEAD' ? null : upstream.body, { status: upstream.status, headers: out });
}

/** Latest release info, cached per Cloudflare location. A stale copy covers GitHub outages and rate limits. */
async function latest(env, ctx) {
  return cached(ctx, 'latest', async () => {
    try {
      let rel = await github(env, `https://api.github.com/repos/${REPO}/releases/latest`);
      if (!findApk(rel)) {
        // Defensive: if the latest release ever lacks an APK, use the newest published release that has one.
        const list = await github(env, `https://api.github.com/repos/${REPO}/releases?per_page=20`);
        rel = list.find((r) => !r.draft && !r.prerelease && findApk(r));
        if (!rel) throw new Error('no release with an APK');
      }
      return toInfo(rel);
    } catch (err) {
      // GitHub allows only 60 anonymous API calls an hour per IP, and Cloudflare's IPs are shared, so the API can
      // refuse us. The release pages themselves don't count against that limit.
      console.warn(`API lookup failed (${err.message}); using the release pages`);
      return fromPages(await latestTag());
    }
  });
}

async function byTag(env, ctx, tag) {
  return cached(ctx, `tag-${tag}`, () => fromPages(tag));
}

/** The tag GitHub's "latest release" page redirects to, e.g. v0.7.3. */
async function latestTag() {
  const res = await fetch(`https://github.com/${REPO}/releases/latest`, { redirect: 'manual', headers: { 'User-Agent': 'daanvia-download' } });
  const tag = (res.headers.get('Location') || '').match(/\/releases\/tag\/([^/?#]+)$/);
  if (!tag) throw new Error(`no latest tag (status ${res.status})`);
  return decodeURIComponent(tag[1]);
}

/** Release info without the API: the asset's fixed name, its size from a HEAD request, date and notes from the feed. */
async function fromPages(tag) {
  const version = tag.replace(/^v/, '');
  const name = `daanvia-${version}.apk`;
  const url = `https://github.com/${REPO}/releases/download/${tag}/${name}`;
  const head = await fetch(url, { method: 'HEAD', redirect: 'follow', headers: { 'User-Agent': 'daanvia-download' } });
  if (!head.ok) throw new Error(`no ${name} in ${tag} (status ${head.status})`);
  const info = {
    version,
    name,
    size: Number(head.headers.get('Content-Length')) || 0,
    url,
    publishedAt: null,
    releaseUrl: `https://github.com/${REPO}/releases/tag/${tag}`,
    notes: '',
  };
  try {
    const feed = await (await fetch(`https://github.com/${REPO}/releases.atom`, { headers: { 'User-Agent': 'daanvia-download' } })).text();
    const entry = feed.split('<entry>').find((e) => e.includes(`/releases/tag/${tag}"`));
    if (entry) {
      info.publishedAt = (entry.match(/<updated>([^<]+)<\/updated>/) || [])[1] || null;
      info.notes = htmlToNotes(decode((entry.match(/<content type="html">([\s\S]*?)<\/content>/) || [])[1] || ''));
    }
  } catch {
    // Date and notes are nice to have; the download works without them.
  }
  return info;
}

/** The feed's release notes are HTML; turn them back into the "**Heading**" + "- item" form of the release body. */
function htmlToNotes(html) {
  return decode(
    html
      .replace(/<li>\s*/g, '\n- ')
      .replace(/<p>\s*<strong>([\s\S]*?)<\/strong>\s*<\/p>/g, '\n**$1**\n')
      .replace(/<h\d[^>]*>([\s\S]*?)<\/h\d>/g, '\n**$1**\n')
      .replace(/<\/(p|ul|ol|li)>/g, '\n')
      .replace(/<[^>]+>/g, ''),
  )
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .join('\n');
}

function decode(s) {
  return s
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/&amp;/g, '&');
}

async function cached(ctx, name, load) {
  const cache = caches.default;
  const freshKey = new Request(`https://daanvia-download.cache/${name}/fresh`);
  const staleKey = new Request(`https://daanvia-download.cache/${name}/stale`);

  const hit = await cache.match(freshKey);
  if (hit) return hit.json();

  try {
    const info = await load();
    const body = JSON.stringify(info);
    ctx.waitUntil(Promise.all([
      cache.put(freshKey, new Response(body, { headers: { 'Cache-Control': `max-age=${FRESH_SECONDS}` } })),
      cache.put(staleKey, new Response(body, { headers: { 'Cache-Control': `max-age=${STALE_SECONDS}` } })),
    ]));
    return info;
  } catch (err) {
    const stale = await cache.match(staleKey);
    if (stale) return stale.json();
    throw err;
  }
}

async function github(env, apiUrl) {
  const headers = {
    Accept: 'application/vnd.github+json',
    'User-Agent': 'daanvia-download',
    'X-GitHub-Api-Version': '2022-11-28',
  };
  if (env.GITHUB_TOKEN) headers.Authorization = `Bearer ${env.GITHUB_TOKEN}`;
  const res = await fetch(apiUrl, { headers });
  if (!res.ok) throw new Error(`GitHub ${res.status} for ${apiUrl}`);
  return res.json();
}

function findApk(rel) {
  return (rel.assets || []).find((a) => APK.test(a.name));
}

function toInfo(rel) {
  const asset = findApk(rel);
  if (!asset) throw new Error(`release ${rel.tag_name} has no APK`);
  return {
    version: asset.name.match(APK)[1],
    name: asset.name,
    size: asset.size,
    url: asset.browser_download_url,
    publishedAt: rel.published_at,
    releaseUrl: rel.html_url,
    notes: rel.body || '',
  };
}

function publicInfo(info) {
  const { url, ...rest } = info;
  return rest;
}

function cors() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
    'Access-Control-Allow-Headers': 'Range',
    'Access-Control-Expose-Headers': 'Content-Length, Content-Range, X-Daanvia-Version',
  };
}
