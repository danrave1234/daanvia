import type { Metadata } from 'next';
import { Wordmark } from '@/components/brand';
import { REPO_URL } from '@/lib/release';

export const metadata: Metadata = {
  title: 'Privacy',
  description: 'What Daanvia keeps on your phone, what it sends, and why.',
  alternates: { canonical: '/privacy' },
};

const UPDATED = '17 November 2026';

export default function Privacy() {
  return (
    <>
      <header className="top">
        <div className="wrap top-in">
          <a href="/" aria-label="Daanvia home">
            <Wordmark />
          </a>
          <nav aria-label="Sections">
            <a href="/">Home</a>
          </nav>
        </div>
      </header>
      <main id="main" className="wrap section doc">
        <p className="eyebrow">Privacy</p>
        <h1 className="h2">What Daanvia keeps, and what it sends</h1>
        <p className="fine">Updated {UPDATED}. This covers the Daanvia Beta Android app and this website.</p>

        <h2>In short</h2>
        <p>
          Daanvia has no accounts, no ads and no tracking. Your location, your routes, your saved places and your bike’s pairing details
          stay on your phone. The app only goes online for the things listed below.
        </p>

        <h2>What stays on your phone</h2>
        <ul>
          <li>
            <strong>Where you are and where you go.</strong> Routes are planned on the phone (Valhalla). Recent rides and saved places are
            stored in the app.
          </li>
          <li>
            <strong>Your bike.</strong> The pairing details from the bike’s QR code are kept in the app to reconnect. The QR is read on the
            phone and its link is never opened.
          </li>
          <li>
            <strong>Notifications on the bike</strong> (optional). With Android’s Notification access turned on, Daanvia reads a new call
            or message only to draw its card on your bike display: the app, who it’s from and the start of the message. It is not saved
            and not sent anywhere. So you can turn apps off, Daanvia remembers which apps have sent notifications, by name only.
          </li>
          <li>
            <strong>The camera</strong> is used only to scan the bike’s QR code. No picture is kept.
          </li>
        </ul>

        <h2>What goes over the internet, and when</h2>
        <ul>
          <li>
            <strong>Map tiles.</strong> With signal, the online map comes from OpenFreeMap, which sees the map area being loaded, as any
            map website does. Downloaded areas need no connection.
          </li>
          <li>
            <strong>Online search.</strong> When you search with signal, the words you type and a rounded position (about 100 m) go to
            Photon by komoot, so nearby results come first.
          </li>
          <li>
            <strong>Parking and fuel stops.</strong> With signal, Daanvia checks with OpenStreetMap’s Overpass servers that a parking lot
            is still there, and looks up fuel stops around a rounded position.
          </li>
          <li>
            <strong>Map downloads and app updates</strong> come from the Daanvia releases on GitHub.
          </li>
          <li>
            <strong>Google Maps</strong>, only if you tap the option to search there: your search opens in Google Maps.
          </li>
          <li>
            <strong>Diagnostics</strong> (during the beta; turn it off in Settings › Help › Send diagnostics to the developer). Every few
            minutes the app sends its connection and ride log, with the app version, the phone model, the Android version and a random id
            made for this install. Each line is cleaned before it leaves the phone: it never includes where you are or where you’re going,
            passwords or device addresses. The log is stored on Cloudflare, used only to fix problems, and deleted after 30 days.
          </li>
          <li>
            <strong>Share navigation report</strong> sends nothing by itself. It opens Android’s share sheet and you choose where it goes.
            Locations, destinations and pairing details are left out of it.
          </li>
        </ul>

        <h2>Permissions</h2>
        <ul>
          <li>
            <strong>Location</strong>: turn-by-turn directions and the map. Not sent anywhere except the rounded positions above.
          </li>
          <li>
            <strong>Nearby devices</strong>: finding your bike’s Wi-Fi Direct connection.
          </li>
          <li>
            <strong>Notifications</strong>: the ride’s Reconnect and Exit controls on your lock screen.
          </li>
          <li>
            <strong>Notification access</strong> (optional): the cards on the bike display, as described above.
          </li>
          <li>
            <strong>Camera</strong>: scanning the bike’s QR code.
          </li>
        </ul>

        <h2>This website</h2>
        <p>
          The site has no cookies, analytics or trackers. It is hosted on Vercel, and the Download button is served by a Cloudflare
          Worker. Both keep the usual short-lived server logs.
        </p>

        <h2>Questions</h2>
        <p>
          Open an issue on{' '}
          <a href={`${REPO_URL}/issues`} rel="noopener">
            GitHub
          </a>
          . Daanvia is not affiliated with QJ Motor or Carbit.
        </p>
      </main>
      <footer className="foot">
        <div className="wrap foot-in">
          <div>
            <Wordmark />
            <p className="fine">Offline motorcycle navigation for the Philippines.</p>
          </div>
        </div>
      </footer>
    </>
  );
}
