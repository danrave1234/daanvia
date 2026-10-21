import { Icon, Mark, Wordmark, type IconName } from '@/components/brand';
import { Clip, Film } from '@/components/media';
import { DOWNLOAD_URL, REPO_URL, formatDate, formatSize, latestRelease, parseNotes, type Release } from '@/lib/release';

// Re-read the latest GitHub release at most every 10 minutes.
export const revalidate = 600;

type Shot = { kind: 'clip'; src: string; poster: string; alt: string } | { kind: 'image'; src: string; alt: string };

interface Feature {
  id: string;
  icon: IconName;
  eyebrow: string;
  title: string;
  body: React.ReactNode;
  points?: React.ReactNode[];
  shot: Shot;
  frame?: 'phone' | 'tft';
}

const features: Feature[] = [
  {
    id: 'offline',
    icon: 'offline',
    eyebrow: 'Offline maps',
    title: 'Download the part of the Philippines you ride',
    body: (
      <>
        Move the map so the frame covers where you ride. Daanvia shows the size in km and MB, and after one download the map,
        search, parking and directions all work without signal.
      </>
    ),
    points: [
      'Map areas cover the whole country, from Luzon to Mindanao.',
      <>Find your town first: 1,650 towns are built in, even before any download.</>,
      <>When new map data is out you get “New map data for your area · Update”.</>,
      <>Settings › Maps shows what uses space, with Free up space.</>,
    ],
    shot: { kind: 'clip', src: '/media/area.mp4', poster: '/media/area.webp', alt: 'Framing a map area and seeing its size before downloading' },
    frame: 'phone',
  },
  {
    id: 'search',
    icon: 'search',
    eyebrow: 'Search',
    title: 'Every branch, named the way you say it',
    body: (
      <>
        Chains are listed by branch, with the road and town: <q>Jollibee · EDSA, Makati</q>. Type a place and its area, like{' '}
        <q>chowking guadalupe</q>, and the right branch comes first.
      </>
    ),
    points: [
      'Offline search across your downloaded area, nearest first, accents ignored.',
      'With signal it also searches OpenStreetMap (Photon) for every branch and street in the country.',
      'Places you found online stay on the phone, so you can find them again without signal.',
      <>Parking inside a place shows under it: SM City Cebu › its parking.</>,
    ],
    shot: { kind: 'clip', src: '/media/search.mp4', poster: '/media/search.webp', alt: 'Searching Jollibee and getting each branch by street and city, with parking under it' },
    frame: 'phone',
  },
  {
    id: 'route',
    icon: 'route',
    eyebrow: 'Routes on the phone',
    title: 'Turn-by-turn for a 160 cc scooter, with no signal',
    body: (
      <>
        Routes are planned on your phone by Valhalla, set for a 160 cc scooter, so they skip expressways and toll roads. Miss a turn
        and Daanvia finds a new way on its own.
      </>
    ),
    points: [
      'Spoken directions as you near each turn.',
      'A tilted follow camera that turns with the road.',
      'Arrival time on the phone, the rest screen and the bike display.',
      'Times are estimates and don’t include live traffic.',
    ],
    shot: { kind: 'clip', src: '/media/ride.mp4', poster: '/media/ride.webp', alt: 'Riding with turn-by-turn directions on a tilted map' },
    frame: 'phone',
  },
  {
    id: 'parking',
    icon: 'parking',
    eyebrow: 'Parking',
    title: 'Know where to park before you arrive',
    body: (
      <>
        The route card tells you <q>3 parking spots near arrival</q>, and P pins show around your destination on the phone and on
        the bike display.
      </>
    ),
    points: [
      'Each lot shows if motorcycles can park, opening hours, price and who it’s for, from OpenStreetMap.',
      <>With signal, Daanvia checks the lot is <q>Still on the map</q>.</>,
      <>Saw the rate at the gate? Save it, and <q>₱20 / hour</q> shows on that pin for 30 days.</>,
    ],
    shot: { kind: 'image', src: '/media/route.webp', alt: 'Route preview with three parking spots near arrival' },
    frame: 'phone',
  },
  {
    id: 'gates',
    icon: 'gate',
    eyebrow: 'Gated places',
    title: 'Guardhouse ahead? You’ll know',
    body: (
      <>
        Daanvia knows tens of thousands of gates. When your route passes one, the route card says{' '}
        <q>The route passes a guardhouse gate. Have your ID ready.</q> If a place is only reachable through a private road, it tells
        you it’s inside a private subdivision, so you can ride to a spot outside the gate instead.
      </>
    ),
    shot: { kind: 'image', src: '/media/gate.webp', alt: 'Route card with a guardhouse gate notice' },
    frame: 'phone',
  },
];

const bike: Feature[] = [
  {
    id: 'tft',
    icon: 'bike',
    eyebrow: 'On your dashboard',
    title: 'Directions on your bike’s TFT',
    body: (
      <>
        Daanvia draws its own landscape navigation screen and sends it to the dashboard as video over Wi-Fi Direct: a tilted map,
        the next turn with its street, and MIN / KM / ARRIVE. Made for QJ Motor / ATR 160 dashboards with the Android QR screen.
      </>
    ),
    points: [
      <>Pair once: open the dashboard’s Android QR screen and tap <q>Scan dashboard QR</q>.</>,
      'Drawn on the phone’s GPU at the rate your dashboard takes, up to 30 frames a second.',
      'Daanvia learns each bike’s screen size and shape, and remembers it.',
      'Parking pins near your destination show on the bike too.',
    ],
    shot: { kind: 'clip', src: '/media/tft.mp4', poster: '/media/tft.webp', alt: 'The navigation screen Daanvia sends to the bike display' },
    frame: 'tft',
  },
  {
    id: 'layout',
    icon: 'layout',
    eyebrow: 'Per-bike layout',
    title: 'Move things out from under your gauges',
    body: (
      <>
        In Settings › Bike display, drag the Turn and Trip cards and the arrow where your speedometer doesn’t cover them, and drag a
        corner to resize. The bike follows along live.
      </>
    ),
    points: [
      <>Fit my TFT: set the left, top and right edges and the bike footer, so nothing is cut off.</>,
      'Text size from 100 to 140%, or the Large turns layout.',
      'Saved for each bike.',
    ],
    shot: { kind: 'clip', src: '/media/layout.mp4', poster: '/media/layout.webp', alt: 'Dragging the turn and trip cards in the bike display layout editor' },
    frame: 'tft',
  },
];

const small: { icon: IconName; title: string; body: React.ReactNode }[] = [
  {
    icon: 'link',
    title: 'Connects by itself',
    body: 'With the app open, and all through a ride even with the phone in your pocket, Daanvia looks for your saved dashboard and connects as soon as its QR screen is waiting.',
  },
  {
    icon: 'moon',
    title: 'Rest the phone',
    body: 'When the bike shows directions and you haven’t touched the phone for 15 seconds, it goes to a dark screen with just the next turn, to save battery. Tap to bring the map back.',
  },
  {
    icon: 'mirror',
    title: 'Mirror your screen',
    body: 'Put the whole phone screen on the bike display, for Google Maps, Waze or music. When you stop sharing, the bike goes back to Daanvia’s directions.',
  },
  {
    icon: 'locate',
    title: 'Finds you like Google Maps',
    body: 'Your last spot shows at once, then Wi-Fi, cell and GPS fixes, with a beam that turns with your phone and a circle showing how sure it is.',
  },
  {
    icon: 'tap',
    title: 'Tap a name, ride there',
    body: 'With signal the map shows shops, food and services with icons by type. Tap a name on the map and the route is ready.',
  },
  {
    icon: 'resume',
    title: 'Pick up a cut-short ride',
    body: 'If Android closes the app mid-ride, the next launch offers “Your ride to … was cut short · Continue”.',
  },
  {
    icon: 'sun',
    title: 'Day and night maps',
    body: 'The map turns dark after sunset where you’re riding and light again at sunrise. Or pick Day or Night yourself.',
  },
  {
    icon: 'star',
    title: 'Saved places from Google Maps',
    body: 'Share a place from Google Maps to “Save in Daanvia”, or open geo: and Maps links. Star any place to keep it.',
  },
  {
    icon: 'update',
    title: 'Updates inside the app',
    body: 'Settings › About checks for new versions, downloads them and checks the file before Android installs it.',
  },
];

const installSteps: React.ReactNode[] = [
  <>
    Open this page <strong>on your Android phone</strong> and tap <strong>Download for Android</strong>. Wi-Fi is best; the app is
    about 35 MB.
  </>,
  <>
    Open <strong>daanvia-…apk</strong> from the download notification or your Downloads folder. If Android says it can’t install
    unknown apps from your browser, tap <strong>Settings</strong>, turn on <strong>Allow from this source</strong>, and go back.
  </>,
  <>
    Tap <strong>Install</strong>. Daanvia isn’t on the Play Store yet, so Google Play Protect may say it doesn’t recognise the app:
    tap <strong>More details › Install anyway</strong>.
  </>,
  <>
    Open Daanvia, allow location, then frame your area and tap <strong>Download</strong>. After that it works without signal.
  </>,
  <>
    To pair your bike: park, close Carbit Ride, keep the dashboard’s <strong>Android QR screen</strong> open, turn Wi-Fi on and hotspot
    off. Then Settings › Bike › <strong>Scan dashboard QR</strong>.
  </>,
];

const faqs: { q: string; a: React.ReactNode }[] = [
  {
    q: 'Does it work without mobile data?',
    a: 'Yes, inside the map areas you download: the map, search, parking, routing and the bike display all work offline. With signal you also get detailed shop and building labels and online search.',
  },
  {
    q: 'Which bikes does it work with?',
    a: 'Dashboards that pair with an Android QR screen (the Carbit / EasyConn kind), such as the QJ Motor / ATR 160. The phone part works on its own, with or without a bike.',
  },
  {
    q: 'Which phones?',
    a: 'Android 10 or newer. One APK works on 64-bit and 32-bit ARM phones. There’s no iPhone version.',
  },
  {
    q: 'Do I need an account? Is it free?',
    a: 'No account and no sign-in. The app is free to download here.',
  },
  {
    q: 'How do I update?',
    a: 'Settings › About › Check, or download again from this page and install over the old app. Your saved places and maps stay.',
  },
  {
    q: 'Is it safe to use while riding?',
    a: 'Set your destination before you ride and pair the bike while parked. Daanvia is a navigation aid and isn’t certified for road use; always follow the road, signs and traffic rules.',
  },
];

export default async function Home() {
  const rel = await latestRelease();
  return (
    <>
      <a className="skip" href="#main">
        Skip to content
      </a>
      <header className="top">
        <div className="wrap top-in">
          <a href="#" aria-label="Daanvia home">
            <Wordmark />
          </a>
          <nav aria-label="Sections">
            <a href="#features">Features</a>
            <a href="#bike">Bike display</a>
            <a href="#videos">Videos</a>
            <a href="#install">Install</a>
          </nav>
          <a className="btn btn-sm" href={DOWNLOAD_URL} download>
            Download
          </a>
        </div>
      </header>

      <main id="main">
        <Hero rel={rel} />

        <section id="features" className="wrap section" aria-labelledby="features-h">
          <p className="eyebrow">What it does</p>
          <h2 id="features-h" className="h2">
            Made for riding in the Philippines
          </h2>
          <div className="features">
            {features.map((f, i) => (
              <FeatureRow key={f.id} f={f} flip={i % 2 === 1} />
            ))}
          </div>
        </section>

        <section id="bike" className="bike-band" aria-labelledby="bike-h">
          <div className="wrap section">
            <p className="eyebrow">Bike display</p>
            <h2 id="bike-h" className="h2">
              Your dashboard becomes the navigator
            </h2>
            <div className="features">
              {bike.map((f, i) => (
                <FeatureRow key={f.id} f={f} flip={i % 2 === 1} />
              ))}
            </div>
          </div>
        </section>

        <section className="wrap section" aria-labelledby="more-h">
          <p className="eyebrow">And the small things</p>
          <h2 id="more-h" className="h2">
            Thought through for daily rides
          </h2>
          <ul className="grid-small">
            {small.map((s) => (
              <li key={s.title}>
                <span className="glyph">
                  <Icon name={s.icon} />
                </span>
                <h3>{s.title}</h3>
                <p>{s.body}</p>
              </li>
            ))}
          </ul>
          <div className="pair-shots">
            <figure>
              <img src="/media/home.webp" alt="Location dot with compass beam and place icons on the night map" loading="lazy" width={540} height={1200} />
              <figcaption>Night</figcaption>
            </figure>
            <figure>
              <img src="/media/day.webp" alt="The same map in the day theme" loading="lazy" width={540} height={1200} />
              <figcaption>Day</figcaption>
            </figure>
            <figure>
              <img src="/media/rest.webp" alt="Rest-phone screen showing just the next turn" loading="lazy" width={540} height={1200} />
              <figcaption>Rest the phone</figcaption>
            </figure>
          </div>
        </section>

        <section id="videos" className="wrap section" aria-labelledby="videos-h">
          <p className="eyebrow">Watch</p>
          <h2 id="videos-h" className="h2">
            See it, then set it up
          </h2>
          <div className="films">
            <figure className="film-main">
              <Film src="/media/promo.mp4" poster="/media/promo.webp" label="Daanvia in 40 seconds" />
              <figcaption>Daanvia in 40 seconds</figcaption>
            </figure>
            <figure>
              <Film src="/media/tut-install.mp4" poster="/media/tut-install.webp" label="How to install the APK" />
              <figcaption>Install the APK</figcaption>
            </figure>
            <figure>
              <Film src="/media/tut-area.mp4" poster="/media/tut-area.webp" label="How to download your map area" />
              <figcaption>Download your map area</figcaption>
            </figure>
            <figure>
              <Film src="/media/tut-bike.mp4" poster="/media/tut-bike.webp" label="How to pair your bike display" />
              <figcaption>Pair your bike display</figcaption>
            </figure>
          </div>
        </section>

        <section id="install" className="wrap section" aria-labelledby="install-h">
          <div className="install">
            <div>
              <p className="eyebrow">Install</p>
              <h2 id="install-h" className="h2">
                Get Daanvia on your phone
              </h2>
              <ol className="steps">
                {installSteps.map((s, i) => (
                  <li key={i}>
                    <span className="num">{i + 1}</span>
                    <p>{s}</p>
                  </li>
                ))}
              </ol>
            </div>
            <aside className="get" aria-label="Download">
              <Mark size={64} />
              <p className="get-title">Daanvia for Android</p>
              <DownloadButton rel={rel} />
              <VersionLine rel={rel} />
              <p className="fine">Android 10 or newer · one APK for every ARM phone · no account</p>
            </aside>
          </div>
        </section>

        {rel && rel.notes.trim() && <WhatsNew rel={rel} />}

        <section className="wrap section" aria-labelledby="faq-h">
          <p className="eyebrow">Questions</p>
          <h2 id="faq-h" className="h2">
            Good to know
          </h2>
          <div className="faq">
            {faqs.map((f) => (
              <details key={f.q}>
                <summary>{f.q}</summary>
                <p>{f.a}</p>
              </details>
            ))}
          </div>
        </section>
      </main>

      <footer className="foot">
        <div className="wrap foot-in">
          <div>
            <Wordmark />
            <p className="fine">Offline motorcycle navigation for the Philippines.</p>
          </div>
          <div className="credits">
            <p>
              Map data ©{' '}
              <a href="https://www.openstreetmap.org/copyright" rel="noopener">
                OpenStreetMap contributors
              </a>
              , available under the{' '}
              <a href="https://opendatacommons.org/licenses/odbl/" rel="noopener">
                Open Database License
              </a>
              . Online map tiles by{' '}
              <a href="https://openfreemap.org" rel="noopener">
                OpenFreeMap
              </a>
              ; online search by{' '}
              <a href="https://photon.komoot.io" rel="noopener">
                Photon
              </a>
              .
            </p>
            <p>
              Built with MapLibre Native (BSD-2-Clause), Valhalla and Valhalla Mobile (MIT), ZXing (Apache-2.0) and the Figtree font
              (OFL). Full notices are in the app under Settings › About.
            </p>
            <p>Daanvia is a navigation aid and isn’t certified for road use. Not affiliated with QJ Motor or Carbit.</p>
            <p>
              <a href={REPO_URL} rel="noopener">
                Source and releases on GitHub
              </a>
            </p>
          </div>
        </div>
      </footer>

      <div className="dock" aria-hidden={false}>
        <DownloadButton rel={rel} compact />
      </div>
    </>
  );
}

function Hero({ rel }: { rel: Release | null }) {
  return (
    <section className="hero" aria-labelledby="hero-h">
      <div className="hero-bg" aria-hidden />
      <div className="wrap hero-in">
        <div className="hero-copy">
          <p className="eyebrow">Android · offline · free</p>
          <h1 id="hero-h">
            Ride anywhere in the Philippines. <span className="accent">No signal needed.</span>
          </h1>
          <p className="lead">
            Daanvia plans your route on the phone, finds the branch and the parking, and puts turn-by-turn directions on your QJ Motor
            / ATR 160 dashboard.
          </p>
          <div className="cta">
            <DownloadButton rel={rel} />
            <a className="btn btn-ghost" href="#videos">
              Watch the video
            </a>
          </div>
          <VersionLine rel={rel} />
        </div>
        <div className="hero-visual">
          <div className="phone">
            <Clip src="/media/ride-demo.mp4" poster="/media/ride-demo.webp" label="Daanvia giving turn-by-turn directions (demo ride)" />
          </div>
          <div className="tft hero-tft">
            <Clip src="/media/tft.mp4" poster="/media/tft.webp" label="The same ride on the bike display" />
          </div>
        </div>
      </div>
    </section>
  );
}

function FeatureRow({ f, flip }: { f: Feature; flip: boolean }) {
  return (
    <article id={f.id} className={`feature ${flip ? 'flip' : ''} ${f.frame === 'tft' ? 'wide' : ''}`}>
      <div className="feature-copy">
        <p className="tag">
          <Icon name={f.icon} size={18} /> {f.eyebrow}
        </p>
        <h3>{f.title}</h3>
        <p className="body">{f.body}</p>
        {f.points && (
          <ul className="points">
            {f.points.map((p, i) => (
              <li key={i}>{p}</li>
            ))}
          </ul>
        )}
      </div>
      <div className={f.frame === 'tft' ? 'tft' : 'phone'}>
        {f.shot.kind === 'clip' ? (
          <Clip src={f.shot.src} poster={f.shot.poster} label={f.shot.alt} />
        ) : (
          <img src={f.shot.src} alt={f.shot.alt} loading="lazy" width={540} height={1200} />
        )}
      </div>
    </article>
  );
}

function DownloadButton({ rel, compact }: { rel: Release | null; compact?: boolean }) {
  return (
    <a className="btn" href={DOWNLOAD_URL} download={rel?.name ?? true}>
      <Icon name="update" size={20} />
      {compact ? (rel ? `Download v${rel.version}` : 'Download') : 'Download for Android'}
      {compact && rel && <span className="btn-sub">{formatSize(rel.size)}</span>}
    </a>
  );
}

function VersionLine({ rel }: { rel: Release | null }) {
  if (!rel) return <p className="version">Latest version · Android 10+</p>;
  return (
    <p className="version">
      Version {rel.version} · {formatSize(rel.size)} · released {formatDate(rel.publishedAt)}
    </p>
  );
}

function WhatsNew({ rel }: { rel: Release }) {
  const blocks = parseNotes(rel.notes);
  return (
    <section className="wrap section" aria-labelledby="new-h">
      <p className="eyebrow">What’s new</p>
      <h2 id="new-h" className="h2">
        In version {rel.version}
      </h2>
      <div className="notes">
        {blocks.map((b, i) => (
          <div key={i} className="note">
            {b.heading && <h3>{b.heading}</h3>}
            <ul>
              {b.items.map((it, j) => (
                <li key={j}>{it}</li>
              ))}
            </ul>
          </div>
        ))}
      </div>
      <p className="fine">
        Released {formatDate(rel.publishedAt)}.{' '}
        <a href={`${REPO_URL}/releases`} rel="noopener">
          Earlier versions
        </a>
      </p>
    </section>
  );
}
