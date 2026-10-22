import { ImageResponse } from 'next/og';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';

export const alt = 'Daanvia Beta: offline motorcycle navigation for the Philippines';
export const size = { width: 1200, height: 630 };
export const contentType = 'image/png';

export default async function OpenGraphImage() {
  const bg = await readFile(join(process.cwd(), 'public', 'bg-roads.svg'), 'utf8');
  const bgUri = `data:image/svg+xml;base64,${Buffer.from(bg).toString('base64')}`;
  return new ImageResponse(
    (
      <div style={{ width: '100%', height: '100%', display: 'flex', position: 'relative', background: '#0E151C', fontFamily: 'sans-serif' }}>
        <img src={bgUri} width={1200} height={750} style={{ position: 'absolute', top: -60, left: 0, opacity: 0.85 }} />
        <div style={{ position: 'absolute', inset: 0, display: 'flex', background: 'linear-gradient(90deg, #0E151C 38%, rgba(14,21,28,0.2))' }} />
        <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', justifyContent: 'center', padding: '0 72px', width: 760 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
            <svg width="84" height="84" viewBox="0 0 108 108">
              <rect width="108" height="108" rx="24" fill="#16202A" />
              <path d="M54 22 L81 82 Q83 87 78 85 L54 73 L30 85 Q25 87 27 82 Z" fill="#FFB020" />
              <path d="M54 40 L68 72 L54 65.5 L40 72 Z" fill="#16202A" />
            </svg>
            <span style={{ color: '#F2F4F6', fontSize: 64, fontWeight: 800, letterSpacing: -2 }}>daanvia</span>
            <span style={{ color: '#FFB020', fontSize: 26, fontWeight: 800, letterSpacing: 3, border: '3px solid #FFB020', borderRadius: 12, padding: '4px 12px', marginLeft: 4 }}>
              BETA
            </span>
          </div>
          <div style={{ color: '#F2F4F6', fontSize: 58, fontWeight: 800, lineHeight: 1.08, marginTop: 40, letterSpacing: -1.5 }}>
            Ride anywhere in the Philippines.
          </div>
          <div style={{ color: '#FFB020', fontSize: 58, fontWeight: 800, lineHeight: 1.08, letterSpacing: -1.5 }}>No signal needed.</div>
          <div style={{ color: '#C9D3DC', fontSize: 28, marginTop: 28 }}>Offline maps · parking · directions on your bike’s TFT</div>
        </div>
      </div>
    ),
    size,
  );
}
