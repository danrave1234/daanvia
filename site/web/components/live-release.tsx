'use client';

import { createContext, useContext, useEffect, useState } from 'react';
import { Icon } from '@/components/brand';
import { DOWNLOAD_URL, formatDate, formatSize, type Release } from '@/lib/release';

/**
 * The page is rendered with the release that was latest when it was built or last regenerated. To never show
 * an old version line after a release, the browser asks the download Worker for the latest release on load
 * and whenever the tab comes back into view, and every version line and download button follows.
 */
const ReleaseContext = createContext<Release | null>(null);

const newer = (a: string, b: string) => {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pa[i] ?? 0) > (pb[i] ?? 0);
  return false;
};

export function ReleaseProvider({ initial, children }: { initial: Release | null; children: React.ReactNode }) {
  const [rel, setRel] = useState(initial);

  useEffect(() => {
    let stopped = false;
    const check = async () => {
      try {
        const res = await fetch(new URL('latest.json', DOWNLOAD_URL), { cache: 'no-store' });
        if (!res.ok) return;
        const w = (await res.json()) as { version: string; name: string; size: number; publishedAt: string | null; releaseUrl: string; notes: string };
        if (stopped || !w.version) return;
        setRel((cur) =>
          !cur || newer(w.version, cur.version)
            ? { version: w.version, name: w.name, size: w.size, publishedAt: w.publishedAt ?? '', url: w.releaseUrl, notes: w.notes ?? '' }
            : cur,
        );
      } catch {
        // Offline or the Worker is unreachable: keep what the page was rendered with.
      }
    };
    check();
    const onShow = () => document.visibilityState === 'visible' && check();
    document.addEventListener('visibilitychange', onShow);
    return () => {
      stopped = true;
      document.removeEventListener('visibilitychange', onShow);
    };
  }, []);

  return <ReleaseContext.Provider value={rel}>{children}</ReleaseContext.Provider>;
}

export function DownloadButton({ compact }: { compact?: boolean }) {
  const rel = useContext(ReleaseContext);
  return (
    <a className="btn" href={DOWNLOAD_URL} download={rel?.name ?? true}>
      <Icon name="update" size={20} />
      {compact ? (rel ? `Download Beta ${rel.version}` : 'Download Daanvia Beta') : 'Download Daanvia Beta'}
      {compact && rel && <span className="btn-sub">{formatSize(rel.size)}</span>}
    </a>
  );
}

export function VersionLine() {
  const rel = useContext(ReleaseContext);
  if (!rel) return <p className="version">Daanvia Beta, latest version · Android 10+</p>;
  return (
    <p className="version">
      Daanvia Beta {rel.version} · {formatSize(rel.size)}
      {formatDate(rel.publishedAt) && <> · released {formatDate(rel.publishedAt)}</>}
    </p>
  );
}
