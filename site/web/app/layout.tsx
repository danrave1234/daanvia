import type { Metadata, Viewport } from 'next';
import { Figtree } from 'next/font/google';
import './globals.css';

const figtree = Figtree({ subsets: ['latin'], weight: ['400', '500', '600', '700', '800'], display: 'swap', variable: '--font-figtree' });

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL || 'https://daanvia-app.vercel.app';
const description =
  'Offline motorcycle navigation for the Philippines. Routes without signal, branch-level search, parking near arrival, and directions on your QJ Motor / ATR 160 dashboard. Free Android app.';

export const metadata: Metadata = {
  metadataBase: new URL(SITE_URL),
  title: { default: 'Daanvia Beta: offline motorcycle navigation for the Philippines', template: '%s · Daanvia Beta' },
  description,
  applicationName: 'Daanvia Beta',
  keywords: ['motorcycle navigation', 'offline maps Philippines', 'QJ Motor', 'ATR 160', 'TFT navigation', 'Android', 'Daanvia', 'Daanvia Beta'],
  openGraph: {
    type: 'website',
    url: SITE_URL,
    siteName: 'Daanvia Beta',
    title: 'Daanvia Beta: offline motorcycle navigation for the Philippines',
    description,
    locale: 'en_PH',
  },
  twitter: { card: 'summary_large_image', title: 'Daanvia Beta', description },
  alternates: { canonical: '/' },
};

export const viewport: Viewport = {
  themeColor: '#0E151C',
  colorScheme: 'dark',
  width: 'device-width',
  initialScale: 1,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={figtree.variable}>
      <body>{children}</body>
    </html>
  );
}
