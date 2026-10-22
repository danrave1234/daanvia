import React from 'react';
import { AbsoluteFill, interpolate, Sequence, useCurrentFrame } from 'remotion';
import { Backdrop, C, Eyebrow, Fade, fontFamily, Note, Phone, Tft } from './ui';

const S = 30;

type Shot = { at: number; len: number; kind: 'phone' | 'tft'; video?: string; image?: string; startFrom?: number };
type Step = { at: number; text: React.ReactNode };

export interface TutorialSpec {
  title: string;
  eyebrow: string;
  seconds: number;
  steps: Step[];
  shots: Shot[];
  note?: string;
}

const StepRow: React.FC<{ n: number; step: Step; next?: number }> = ({ n, step, next }) => {
  const frame = useCurrentFrame();
  const start = step.at * S;
  const end = next !== undefined ? next * S : Infinity;
  const shown = interpolate(frame, [start - 6, start + 6], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const active = frame >= start && frame < end;
  const done = frame >= end;
  return (
    <div
      style={{
        display: 'flex',
        gap: 26,
        alignItems: 'flex-start',
        opacity: shown * (done ? 0.5 : 1),
        transform: `translateX(${(1 - shown) * -24}px)`,
        padding: '13px 22px',
        borderRadius: 22,
        background: active ? 'rgba(255,176,32,0.10)' : 'transparent',
        border: `2px solid ${active ? 'rgba(255,176,32,0.35)' : 'transparent'}`,
      }}
    >
      <div
        style={{
          flex: 'none',
          width: 46,
          height: 46,
          borderRadius: 16,
          display: 'grid',
          placeItems: 'center',
          background: active ? C.accent : '#1D2833',
          color: active ? C.accentInk : C.accent,
          fontWeight: 800,
          fontSize: 26,
        }}
      >
        {n}
      </div>
      <div style={{ color: active ? C.ink : C.ink2, fontSize: 30, lineHeight: 1.3, fontWeight: active ? 700 : 500, paddingTop: 4 }}>{step.text}</div>
    </div>
  );
};

export const Tutorial: React.FC<{ spec: TutorialSpec }> = ({ spec }) => (
  <AbsoluteFill style={{ fontFamily, background: C.ground }}>
    <Backdrop dim={0.7} />
    <AbsoluteFill style={{ flexDirection: 'row', padding: '64px 100px 90px', gap: 70 }}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <Eyebrow>{spec.eyebrow}</Eyebrow>
        <div style={{ color: C.ink, fontWeight: 800, fontSize: 60, letterSpacing: '-0.025em', margin: '10px 0 24px' }}>{spec.title}</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {spec.steps.map((s, i) => (
            <StepRow key={i} n={i + 1} step={s} next={spec.steps[i + 1]?.at} />
          ))}
        </div>
      </div>
      <div style={{ position: 'relative', width: spec.shots.some((s) => s.kind === 'tft') ? 880 : 460, display: 'grid', placeItems: 'center' }}>
        {spec.shots.map((shot, i) => (
          <Sequence key={i} from={Math.round(shot.at * S)} durationInFrames={Math.round(shot.len * S)} layout="none">
            <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}>
              <Fade inFrames={10} outFrames={10}>
                <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center' }}>
                  {shot.kind === 'phone' ? (
                    <Phone height={900} video={shot.video} image={shot.image} startFrom={shot.startFrom} />
                  ) : (
                    <Tft width={840} video={shot.video} image={shot.image} startFrom={shot.startFrom} />
                  )}
                </div>
              </Fade>
            </div>
          </Sequence>
        ))}
      </div>
    </AbsoluteFill>
    {spec.note && <Note>{spec.note}</Note>}
  </AbsoluteFill>
);

export const installSpec: TutorialSpec = {
  eyebrow: 'Tutorial',
  title: 'Install Daanvia Beta',
  seconds: 34,
  note: 'Recorded on Android 16. Screens differ a little between phones.',
  steps: [
    { at: 0.3, text: <>On your Android phone, open the Daanvia site and tap <b>Download Daanvia Beta</b>.</> },
    { at: 5, text: <>The APK downloads straight to your phone. It’s about 36 MB, so Wi-Fi is best.</> },
    { at: 9.5, text: <>Tap <b>Open</b> on the download, or open it from your Downloads folder.</> },
    { at: 14.5, text: <>Android asks about unknown apps the first time: tap <b>Settings</b>, turn on <b>Allow from this source</b>, then go back.</> },
    { at: 22, text: <>Tap <b>Install</b>. If Play Protect warns about an unknown app, tap <b>More details › Install anyway</b>.</> },
    { at: 28, text: <>Open Daanvia Beta. Later versions update from <b>Settings › About</b>.</> },
  ],
  shots: [
    { at: 0, len: 19.5, kind: 'phone', video: 'install.mp4' },
    { at: 19.2, len: 15, kind: 'phone', image: 'unknown.png' },
  ],
};

export const areaSpec: TutorialSpec = {
  eyebrow: 'Tutorial',
  title: 'Download your map area',
  seconds: 30,
  steps: [
    { at: 0.3, text: <>Open Daanvia Beta. With no map yet, it asks you to <b>choose your map area</b>.</> },
    { at: 4, text: <>Move and zoom so the frame covers where you ride. Or <b>find a city or town</b> to jump there.</> },
    { at: 10, text: <>Check the size in km and MB, then tap <b>Download</b>. Wi-Fi is recommended.</> },
    { at: 15, text: <>Keep Daanvia open until it finishes. After that, it works without signal.</> },
    { at: 20, text: <>Manage areas in <b>Settings › Maps</b>: update, remove, or free up space.</> },
  ],
  shots: [
    { at: 0, len: 11.5, kind: 'phone', video: 'area.mp4' },
    { at: 11.3, len: 8.9, kind: 'phone', image: 'area.png' },
    { at: 20, len: 10.5, kind: 'phone', image: 'settings_maps.png' },
  ],
};

export const bikeSpec: TutorialSpec = {
  eyebrow: 'Tutorial',
  title: 'Pair your bike display',
  seconds: 36,
  note: 'The bike display picture is Daanvia’s own preview of what it sends to the dashboard.',
  steps: [
    { at: 0.3, text: <>Park first. Close Carbit Ride and open the dashboard’s <b>Android QR screen</b>. Wi-Fi on, hotspot off.</> },
    { at: 5.5, text: <>In Daanvia, go to <b>Settings › Bike › Scan dashboard QR</b>.</> },
    { at: 10, text: <>Point at the dashboard’s QR. Tap to focus, or <b>use a photo</b> of it.</> },
    { at: 15, text: <>Daanvia connects over Wi-Fi Direct and your dashboard shows the navigation screen.</> },
    { at: 22, text: <>Next time it connects by itself as soon as the QR screen is waiting.</> },
    { at: 26, text: <><b>Settings › Bike display › Edit display layout</b> moves the cards out from under your gauges.</> },
  ],
  shots: [
    { at: 0, len: 10.2, kind: 'phone', image: 'settings_bike.png' },
    { at: 10, len: 5.2, kind: 'phone', image: 'qr.png' },
    { at: 15, len: 11.2, kind: 'tft', video: 'tft.mp4' },
    { at: 26, len: 10, kind: 'tft', video: 'layout.mp4' },
  ],
};
