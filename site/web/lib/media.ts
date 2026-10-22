import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * A media URL with a short fingerprint of the file's contents, e.g. /media/ride.mp4?v=3f9a1c2b.
 * When a screenshot or clip is replaced, its URL changes, so browsers and Vercel fetch the new one
 * at once instead of showing a cached copy. Computed at build time (server components only).
 */
const seen = new Map<string, string>();

export function media(path: string): string {
  let url = seen.get(path);
  if (!url) {
    try {
      const bytes = readFileSync(join(process.cwd(), 'public', path));
      url = `${path}?v=${createHash('sha256').update(bytes).digest('hex').slice(0, 8)}`;
    } catch {
      url = path;
    }
    seen.set(path, url);
  }
  return url;
}
