import React from 'react';
import { AbsoluteFill, interpolate, Series, useCurrentFrame } from 'remotion';
import { Backdrop, C, Caption, Fade, fontFamily, Mark, Note, Phone, Tft, useIn } from './ui';

const S = 30; // fps

const Split: React.FC<{ children: React.ReactNode; media: React.ReactNode }> = ({ children, media }) => (
  <AbsoluteFill style={{ fontFamily }}>
    <Backdrop />
    <AbsoluteFill style={{ flexDirection: 'row', alignItems: 'center', padding: '0 110px', gap: 80 }}>
      <div style={{ flex: 1 }}>{children}</div>
      <div style={{ display: 'flex', gap: 40, alignItems: 'center' }}>{media}</div>
    </AbsoluteFill>
  </AbsoluteFill>
);

const Rise: React.FC<{ children: React.ReactNode; delay?: number }> = ({ children, delay = 0 }) => {
  const p = useIn(delay, 16);
  return <div style={{ opacity: p, transform: `translateY(${(1 - p) * 80}px) scale(${0.94 + p * 0.06})` }}>{children}</div>;
};

const Intro: React.FC = () => {
  const p = useIn(2, 12);
  const t = useIn(14);
  const s = useIn(24);
  return (
    <AbsoluteFill style={{ fontFamily, alignItems: 'center', justifyContent: 'center' }}>
      <Backdrop dim={0.35} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 36, transform: `scale(${0.8 + p * 0.2})`, opacity: p }}>
        <Mark size={170} />
        <div style={{ color: C.ink, fontWeight: 800, fontSize: 150, letterSpacing: '-0.04em' }}>daanvia</div>
        <div style={{ color: C.accent, fontWeight: 800, fontSize: 40, letterSpacing: '0.14em', border: `4px solid ${C.accent}`, borderRadius: 18, padding: '6px 18px', alignSelf: 'center' }}>BETA</div>
      </div>
      <div style={{ opacity: t, transform: `translateY(${(1 - t) * 20}px)`, color: C.ink, fontWeight: 700, fontSize: 52, marginTop: 40 }}>
        Offline motorcycle navigation for the Philippines
      </div>
      <div style={{ opacity: s, color: C.accent, fontWeight: 700, fontSize: 32, marginTop: 22, letterSpacing: '0.12em', textTransform: 'uppercase' }}>
        On your phone · on your bike’s TFT
      </div>
    </AbsoluteFill>
  );
};

const Outro: React.FC = () => {
  const p = useIn(2, 14);
  const b = useIn(14);
  return (
    <AbsoluteFill style={{ fontFamily, alignItems: 'center', justifyContent: 'center' }}>
      <Backdrop dim={0.4} />
      <div style={{ opacity: p, transform: `scale(${0.85 + p * 0.15})`, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
        <Mark size={150} />
        <div style={{ color: C.ink, fontWeight: 800, fontSize: 96, letterSpacing: '-0.03em', marginTop: 30 }}>Get Daanvia Beta</div>
      </div>
      <div
        style={{
          opacity: b,
          transform: `translateY(${(1 - b) * 24}px)`,
          marginTop: 40,
          background: C.accent,
          color: C.accentInk,
          fontWeight: 800,
          fontSize: 44,
          padding: '26px 56px',
          borderRadius: 28,
          boxShadow: '0 20px 60px -20px rgba(255,176,32,0.6)',
        }}
      >
        Download Daanvia Beta
      </div>
      <div style={{ opacity: b, color: C.ink2, fontSize: 30, marginTop: 30, fontWeight: 500 }}>Free · Android 10 or newer · no account</div>
      <div style={{ position: 'absolute', bottom: 44, color: C.ink3, fontSize: 20 }}>Map data © OpenStreetMap contributors</div>
    </AbsoluteFill>
  );
};

/** Two phones swapping: route preview, then the lot details. */
const ParkingMedia: React.FC = () => {
  const frame = useCurrentFrame();
  const swap = interpolate(frame, [62, 78], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  return (
    <div style={{ position: 'relative', width: 420, height: 920 }}>
      <div style={{ position: 'absolute', opacity: 1 - swap }}>
        <Phone height={880} image="route_parking.png" />
      </div>
      <div style={{ position: 'absolute', opacity: swap }}>
        <Phone height={880} image="plist.png" />
      </div>
    </div>
  );
};

export const PROMO_FRAMES = Math.round(S * (3 + 4.5 + 4.5 + 5 + 4 + 6 + 4.5 + 3.5 + 4));

export const Promo: React.FC = () => (
  <AbsoluteFill style={{ background: C.ground }}>
    <Series>
      <Series.Sequence durationInFrames={3 * S}>
        <Fade inFrames={1}>
          <Intro />
        </Fade>
      </Series.Sequence>

      <Series.Sequence durationInFrames={4.5 * S}>
        <Fade>
          <Split media={<Rise><Phone height={880} video="area.mp4" /></Rise>}>
            <Caption eyebrow="Offline maps" title="Download the part of the Philippines you ride." sub="Then the map, search, parking and directions work without signal." />
          </Split>
        </Fade>
      </Series.Sequence>

      <Series.Sequence durationInFrames={4.5 * S}>
        <Fade>
          <Split media={<Rise><Phone height={880} video="search.mp4" startFrom={45} /></Rise>}>
            <Caption eyebrow="Search" title="Every branch, by street and city." sub={<>“Jollibee · EDSA, Makati”, with the parking under it.</>} />
          </Split>
        </Fade>
      </Series.Sequence>

      <Series.Sequence durationInFrames={5 * S}>
        <Fade>
          <Split media={<Rise><ParkingMedia /></Rise>}>
            <Caption eyebrow="Parking" title="Know where to park before you arrive." sub="The route card lists the parking near your destination, nearest first." />
          </Split>
        </Fade>
      </Series.Sequence>

      <Series.Sequence durationInFrames={4 * S}>
        <Fade>
          <Split media={<Rise><Phone height={880} image="gate.png" /></Rise>}>
            <Caption eyebrow="Gated places" title="Guardhouse ahead? Have your ID ready." sub="Daanvia tells you when the route passes gates." />
          </Split>
        </Fade>
      </Series.Sequence>

      <Series.Sequence durationInFrames={6 * S}>
        <Fade>
          <AbsoluteFill style={{ fontFamily }}>
            <Backdrop />
            <AbsoluteFill style={{ padding: '90px 110px', flexDirection: 'column' }}>
              <Caption eyebrow="Bike display" title="On your phone. And on your bike." size={76} width={1400} />
              <div style={{ display: 'flex', gap: 70, alignItems: 'center', marginTop: 50 }}>
                <Rise delay={6}><Phone height={640} video="ride-demo.mp4" /></Rise>
                <Rise delay={14}><Tft width={980} video="tft.mp4" /></Rise>
              </div>
            </AbsoluteFill>
            <Note>Demo ride, recorded on the phone</Note>
          </AbsoluteFill>
        </Fade>
      </Series.Sequence>

      <Series.Sequence durationInFrames={4.5 * S}>
        <Fade>
          <Split media={<Rise><Tft width={860} video="layout.mp4" /></Rise>}>
            <Caption eyebrow="Per-bike layout" title="Move it out from under your gauges." sub="Drag and resize the turn and trip cards. Saved for each bike." size={76} width={760} />
          </Split>
        </Fade>
      </Series.Sequence>

      <Series.Sequence durationInFrames={3.5 * S}>
        <Fade>
          <Split
            media={
              <>
                <Rise><Phone height={820} image="day.png" /></Rise>
                <Rise delay={8}><Phone height={820} image="night.png" /></Rise>
              </>
            }
          >
            <Caption eyebrow="Day and night" title="Follows the sun where you ride." size={76} width={640} />
          </Split>
        </Fade>
      </Series.Sequence>

      <Series.Sequence durationInFrames={4 * S}>
        <Fade outFrames={1}>
          <Outro />
        </Fade>
      </Series.Sequence>
    </Series>
  </AbsoluteFill>
);
