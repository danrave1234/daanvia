import { loadFont } from '@remotion/google-fonts/Figtree';
import React from 'react';
import { AbsoluteFill, Img, interpolate, OffthreadVideo, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';

export const { fontFamily } = loadFont('normal', { weights: ['500', '700', '800'], subsets: ['latin'] });

export const C = {
  ground: '#0E151C',
  surface: '#17212B',
  ink: '#F2F4F6',
  ink2: '#C9D3DC',
  ink3: '#8494A4',
  accent: '#FFB020',
  accentInk: '#1A1203',
  sea: '#0A1A26',
};

/** Night street map background with a slow drift, matching the website hero. */
export const Backdrop: React.FC<{ dim?: number }> = ({ dim = 0.55 }) => {
  const frame = useCurrentFrame();
  const x = interpolate(frame, [0, 900], [0, -80]);
  return (
    <AbsoluteFill style={{ background: C.ground, overflow: 'hidden' }}>
      <Img src={staticFile('bg-roads.svg')} style={{ position: 'absolute', width: '115%', height: '115%', left: x, top: -40, objectFit: 'cover' }} />
      <AbsoluteFill style={{ background: `linear-gradient(90deg, rgba(14,21,28,${dim + 0.35}) 0%, rgba(14,21,28,${dim}) 55%, rgba(14,21,28,${dim - 0.2}) 100%)` }} />
    </AbsoluteFill>
  );
};

/** 0→1 entrance spring, delayed by `delay` frames. */
export const useIn = (delay = 0, damping = 18) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: frame - delay, fps, config: { damping, mass: 0.7 } });
};

export const Mark: React.FC<{ size: number }> = ({ size }) => (
  <svg width={size} height={size} viewBox="0 0 108 108">
    <rect width="108" height="108" rx="26" fill="#16202A" />
    <path d="M54 22 L81 82 Q83 87 78 85 L54 73 L30 85 Q25 87 27 82 Z" fill={C.accent} />
    <path d="M54 40 L68 72 L54 65.5 L40 72 Z" fill="#16202A" />
    <rect x="52.4" y="51" width="3.2" height="5" rx="1.6" fill={C.accent} />
    <rect x="52.4" y="59.5" width="3.2" height="4.2" rx="1.6" fill={C.accent} />
  </svg>
);

type Media = { video?: string; image?: string; startFrom?: number; playbackRate?: number };

const Screen: React.FC<Media & { radius: number }> = ({ video, image, startFrom, playbackRate, radius }) => (
  <div style={{ position: 'absolute', inset: 0, borderRadius: radius, overflow: 'hidden', background: '#05080B' }}>
    {video && (
      <OffthreadVideo
        src={staticFile(video)}
        startFrom={startFrom}
        playbackRate={playbackRate}
        muted
        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
      />
    )}
    {image && <Img src={staticFile(image)} style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
  </div>
);

/** Phone with the app's 9:20 screen. */
export const Phone: React.FC<Media & { height: number; style?: React.CSSProperties }> = ({ height, style, ...media }) => {
  const width = (height * 9) / 20;
  const pad = height * 0.014;
  return (
    <div
      style={{
        position: 'relative',
        width: width + pad * 2,
        height: height + pad * 2,
        padding: pad,
        borderRadius: height * 0.065,
        background: 'linear-gradient(160deg, #2A3642, #0B1117)',
        boxShadow: '0 0 0 2px rgba(255,255,255,0.06) inset, 0 60px 120px -40px rgba(0,0,0,0.9)',
        ...style,
      }}
    >
      <div style={{ position: 'relative', width, height }}>
        <Screen {...media} radius={height * 0.052} />
      </div>
    </div>
  );
};

/** Motorcycle dashboard (5:3) frame. */
export const Tft: React.FC<Media & { width: number; style?: React.CSSProperties }> = ({ width, style, ...media }) => {
  const height = (width * 3) / 5;
  const pad = width * 0.022;
  return (
    <div
      style={{
        position: 'relative',
        width: width + pad * 2,
        height: height + pad * 2,
        padding: pad,
        borderRadius: width * 0.05,
        background: 'linear-gradient(170deg, #252F39, #090D11 70%)',
        boxShadow: '0 0 0 2px rgba(255,176,32,0.14) inset, 0 50px 100px -40px rgba(0,0,0,0.9)',
        ...style,
      }}
    >
      <div style={{ position: 'relative', width, height }}>
        <Screen {...media} radius={width * 0.026} />
      </div>
    </div>
  );
};

export const Eyebrow: React.FC<{ children: React.ReactNode; size?: number }> = ({ children, size = 26 }) => (
  <div style={{ color: C.accent, fontWeight: 800, fontSize: size, letterSpacing: '0.14em', textTransform: 'uppercase' }}>{children}</div>
);

/** Caption block that rises in: eyebrow, title, optional line. */
export const Caption: React.FC<{ eyebrow?: string; title: React.ReactNode; sub?: React.ReactNode; size?: number; delay?: number; width?: number }> = ({
  eyebrow,
  title,
  sub,
  size = 84,
  delay = 4,
  width = 820,
}) => {
  const a = useIn(delay);
  const b = useIn(delay + 6);
  const c = useIn(delay + 12);
  const rise = (p: number) => ({ opacity: p, transform: `translateY(${(1 - p) * 30}px)` });
  return (
    <div style={{ width, fontFamily }}>
      {eyebrow && <div style={rise(a)}><Eyebrow>{eyebrow}</Eyebrow></div>}
      <div style={{ ...rise(b), color: C.ink, fontWeight: 800, fontSize: size, lineHeight: 1.04, letterSpacing: '-0.025em', marginTop: 18 }}>{title}</div>
      {sub && <div style={{ ...rise(c), color: C.ink2, fontWeight: 500, fontSize: size * 0.4, lineHeight: 1.35, marginTop: 26 }}>{sub}</div>}
    </div>
  );
};

/** Fades a scene in and out at its edges. */
export const Fade: React.FC<{ children: React.ReactNode; inFrames?: number; outFrames?: number }> = ({ children, inFrames = 8, outFrames = 8 }) => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  const o = Math.min(
    interpolate(frame, [0, inFrames], [0, 1], { extrapolateRight: 'clamp' }),
    interpolate(frame, [durationInFrames - outFrames, durationInFrames], [1, 0], { extrapolateLeft: 'clamp' }),
  );
  return <AbsoluteFill style={{ opacity: o }}>{children}</AbsoluteFill>;
};

/** Small "real app recording" note so footage reads as genuine, not a mock-up. */
export const Note: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div style={{ position: 'absolute', left: 96, bottom: 56, color: C.ink3, fontSize: 22, fontFamily, fontWeight: 500 }}>{children}</div>
);
