/** The app's launcher mark: the road chevron from design/logo.html (option A), 108-unit grid. */
export function Mark({ size = 32, framed = true }: { size?: number; framed?: boolean }) {
  return (
    <svg width={size} height={size} viewBox="0 0 108 108" aria-hidden className="mark">
      {framed && <rect width="108" height="108" rx="26" fill="#0E151C" />}
      <path d="M54 22 L81 82 Q83 87 78 85 L54 73 L30 85 Q25 87 27 82 Z" fill="#FFB020" />
      <path d="M54 40 L68 72 L54 65.5 L40 72 Z" fill="#0E151C" />
      <rect x="52.4" y="51" width="3.2" height="5" rx="1.6" fill="#FFB020" />
      <rect x="52.4" y="59.5" width="3.2" height="4.2" rx="1.6" fill="#FFB020" />
    </svg>
  );
}

export function Wordmark() {
  return (
    <span className="wordmark">
      <Mark size={30} />
      <span>daanvia</span>
      <span className="beta">Beta</span>
    </span>
  );
}

/**
 * Feature glyphs drawn for this site on a 24-unit grid with one 1.8 stroke,
 * in the same plain style as the app's own controls.
 */
const paths: Record<string, React.ReactNode> = {
  offline: (
    <>
      <path d="M4 7.5 9 5l6 2.5L20 5v11.5L15 19l-6-2.5L4 19z" />
      <path d="M9 5v11.5M15 7.5V19" />
    </>
  ),
  search: (
    <>
      <circle cx="10.5" cy="10.5" r="6" />
      <path d="m15 15 5 5" />
    </>
  ),
  route: (
    <>
      <circle cx="6" cy="18" r="2" />
      <circle cx="18" cy="6" r="2" />
      <path d="M8 18h6.5a3.5 3.5 0 0 0 0-7h-5a3.5 3.5 0 0 1 0-7H16" />
    </>
  ),
  parking: (
    <>
      <rect x="4" y="4" width="16" height="16" rx="4" />
      <path d="M10 16.5v-9h3a2.75 2.75 0 0 1 0 5.5h-3" />
    </>
  ),
  gate: (
    <>
      <path d="M4 20V9l3-2 3 2v11" />
      <path d="M10 13h10M10 16h10M16 13v3" />
    </>
  ),
  bike: (
    <>
      <rect x="3" y="6" width="18" height="11" rx="3" />
      <path d="M9 20h6M12 17v3" />
      <path d="m9 13 3-4.5 3 4.5-3-1.5z" />
    </>
  ),
  link: (
    <>
      <path d="M5 12.5a7 7 0 0 1 14 0" />
      <path d="M8 13a4 4 0 0 1 8 0" />
      <circle cx="12" cy="15" r="1.4" />
    </>
  ),
  layout: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2.5" />
      <rect x="6" y="8" width="5" height="4" rx="1" />
      <rect x="6" y="14" width="5" height="2" rx="1" />
      <path d="m16 10 1.5 3 1.5-3" />
    </>
  ),
  moon: <path d="M19 14.5A7.5 7.5 0 1 1 9.5 5a6 6 0 0 0 9.5 9.5z" />,
  mirror: (
    <>
      <rect x="3" y="6" width="11" height="8" rx="1.5" />
      <rect x="15.5" y="9" width="5.5" height="10" rx="1.5" />
      <path d="M8.5 18h4" />
    </>
  ),
  locate: (
    <>
      <circle cx="12" cy="13" r="3" />
      <path d="M12 3.5 9.5 8h5z" />
      <circle cx="12" cy="13" r="7.5" opacity=".45" />
    </>
  ),
  tap: (
    <>
      <path d="M9 11V5.5a1.5 1.5 0 0 1 3 0V11l3.5.6a2 2 0 0 1 1.6 2.3L16.5 18a2.5 2.5 0 0 1-2.5 2h-3.2a2.5 2.5 0 0 1-2-1L6 15.5a1.4 1.4 0 0 1 2-2l1 1" />
    </>
  ),
  resume: (
    <>
      <path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3" />
      <path d="M4 4v4h4" />
      <path d="m10.5 9.5 4 2.5-4 2.5z" />
    </>
  ),
  sun: (
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4" />
    </>
  ),
  star: <path d="m12 4 2.4 5 5.4.7-4 3.8 1 5.4L12 16.3 7.2 18.9l1-5.4-4-3.8 5.4-.7z" />,
  update: (
    <>
      <path d="M12 4v11M7.5 10.5 12 15l4.5-4.5" />
      <path d="M5 19h14" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3.5 19 6v6c0 4.2-3 7.2-7 8.5-4-1.3-7-4.3-7-8.5V6z" />
      <path d="m9 12 2.2 2.2L15.5 10" />
    </>
  ),
  qr: (
    <>
      <rect x="4" y="4" width="6" height="6" rx="1" />
      <rect x="14" y="4" width="6" height="6" rx="1" />
      <rect x="4" y="14" width="6" height="6" rx="1" />
      <path d="M14 14h2v2h-2zM18 14h2M14 18v2M17.5 17.5H20V20h-2.5z" />
    </>
  ),
  voice: (
    <>
      <path d="M4 9.5h3.5L12 6v12l-4.5-3.5H4z" />
      <path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11" />
    </>
  ),
};

export type IconName = keyof typeof paths;

export function Icon({ name, size = 22 }: { name: IconName; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      {paths[name]}
    </svg>
  );
}
