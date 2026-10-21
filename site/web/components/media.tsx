'use client';

import { useEffect, useRef, useState } from 'react';

/**
 * A short muted clip that loads only when it scrolls near the screen and plays
 * only while visible, so mobile data isn't spent on clips nobody sees.
 * With reduced motion it stays on its poster until tapped.
 */
export function Clip({ src, poster, label, className }: { src: string; poster: string; label: string; className?: string }) {
  const ref = useRef<HTMLVideoElement>(null);
  const [reduced, setReduced] = useState(false);
  const [paused, setPaused] = useState(true);

  useEffect(() => {
    const video = ref.current;
    if (!video) return;
    const still = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    setReduced(still);
    const io = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          if (!video.src) {
            video.src = src;
            video.load();
          }
          if (!still) video.play().catch(() => {});
        } else if (!video.paused) {
          video.pause();
        }
      },
      { rootMargin: '200px 0px', threshold: 0.25 },
    );
    io.observe(video);
    const sync = () => setPaused(video.paused);
    video.addEventListener('play', sync);
    video.addEventListener('pause', sync);
    return () => {
      io.disconnect();
      video.removeEventListener('play', sync);
      video.removeEventListener('pause', sync);
    };
  }, [src]);

  const toggle = () => {
    const v = ref.current;
    if (!v) return;
    if (!v.src) v.src = src;
    if (v.paused) v.play().catch(() => {});
    else v.pause();
  };

  return (
    <div className={`clip ${className ?? ''}`}>
      <video ref={ref} poster={poster} muted loop playsInline preload="none" aria-label={label} onClick={toggle} />
      {reduced && paused && (
        <button type="button" className="clip-play" onClick={toggle} aria-label={`Play: ${label}`}>
          <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden>
            <path d="M8 5.5v13l10.5-6.5z" fill="currentColor" />
          </svg>
        </button>
      )}
    </div>
  );
}

/** A tutorial or promo video with controls (captions are burned in); nothing downloads until play. */
export function Film({ src, poster, label }: { src: string; poster: string; label: string }) {
  return <video className="film" src={src} poster={poster} controls preload="none" playsInline aria-label={label} />;
}
