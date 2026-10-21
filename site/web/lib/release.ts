/**
 * The latest Daanvia release, read from GitHub at most every 10 minutes (ISR),
 * so the page shows the current version without an edit or redeploy per release.
 *
 * App releases are tagged vX.Y.Z and carry exactly one asset, daanvia-X.Y.Z.apk.
 * The `maps` release (map area zips) is never marked latest and has no such asset.
 */
const REPO = 'danrave1234/daanvia';
const APK = /^daanvia-(\d+\.\d+\.\d+)\.apk$/;

/** Where the Download button points: the Cloudflare Worker that streams the APK. */
export const DOWNLOAD_URL = process.env.NEXT_PUBLIC_DOWNLOAD_URL || 'https://daanvia-download.workers.dev/';

export const REPO_URL = `https://github.com/${REPO}`;

export interface Release {
  version: string;
  name: string;
  size: number;
  publishedAt: string;
  url: string;
  notes: string;
}

interface GitHubRelease {
  tag_name: string;
  html_url: string;
  published_at: string;
  body: string | null;
  draft: boolean;
  prerelease: boolean;
  assets: { name: string; size: number }[];
}

export async function latestRelease(): Promise<Release | null> {
  const headers: Record<string, string> = {
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
  };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  try {
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=10`, {
      headers,
      next: { revalidate: 600 },
    });
    if (!res.ok) return null;
    const list = (await res.json()) as GitHubRelease[];
    for (const rel of list) {
      if (rel.draft || rel.prerelease) continue;
      const apk = rel.assets.find((a) => APK.test(a.name));
      if (!apk) continue;
      return {
        version: apk.name.match(APK)![1],
        name: apk.name,
        size: apk.size,
        publishedAt: rel.published_at,
        url: rel.html_url,
        notes: rel.body ?? '',
      };
    }
  } catch {
    // Fall through to the Worker below.
  }
  // GitHub limits anonymous API calls per IP and Vercel's IPs are shared, so ask the download Worker,
  // which reads the release pages when the API refuses it.
  try {
    const res = await fetch(new URL('latest.json', DOWNLOAD_URL), { next: { revalidate: 600 } });
    if (res.ok) {
      const w = (await res.json()) as { version: string; name: string; size: number; publishedAt: string | null; releaseUrl: string; notes: string };
      return { version: w.version, name: w.name, size: w.size, publishedAt: w.publishedAt ?? '', url: w.releaseUrl, notes: w.notes ?? '' };
    }
  } catch {
    // Both unreachable: the page still renders, just without the version line.
  }
  return null;
}

export function formatSize(bytes: number): string {
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}

export function formatDate(iso: string): string {
  if (!iso || Number.isNaN(Date.parse(iso))) return '';
  return new Intl.DateTimeFormat('en-PH', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'Asia/Manila' }).format(
    new Date(iso),
  );
}

export type NoteBlock = { heading: string | null; items: string[] };

/**
 * Release bodies are written as "**Heading**" lines followed by "- " bullets.
 * Anything else becomes a plain item, so an unusual body still shows.
 */
export function parseNotes(body: string): NoteBlock[] {
  const blocks: NoteBlock[] = [];
  let current: NoteBlock | null = null;
  for (const raw of body.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    const heading = line.match(/^(?:#+\s*)?\*\*(.+?)\*\*$/) || line.match(/^#+\s+(.+)$/);
    if (heading) {
      current = { heading: heading[1], items: [] };
      blocks.push(current);
      continue;
    }
    if (!current) {
      current = { heading: null, items: [] };
      blocks.push(current);
    }
    current.items.push(line.replace(/^[-*]\s+/, '').replace(/\*\*(.+?)\*\*/g, '$1').replace(/`([^`]+)`/g, '$1'));
  }
  return blocks.filter((b) => b.items.length > 0 || b.heading);
}
