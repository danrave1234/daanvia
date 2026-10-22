import type { NextConfig } from 'next';

const DOWNLOAD_URL = process.env.NEXT_PUBLIC_DOWNLOAD_URL || 'https://daanvia-download.danravekeh123.workers.dev/';

const config: NextConfig = {
  // This app lives inside the Android repo; keep Next from treating a parent folder as the workspace root.
  turbopack: { root: __dirname },
  outputFileTracingRoot: __dirname,
  reactStrictMode: true,
  poweredByHeader: false,
  // Short shareable link: daanvia…/download goes to the download Worker, which streams the APK.
  redirects: async () => [{ source: '/download', destination: DOWNLOAD_URL, permanent: false }],
  headers: async () => [
    {
      source: '/(.*)',
      headers: [
        { key: 'X-Content-Type-Options', value: 'nosniff' },
        { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
        { key: 'X-Frame-Options', value: 'DENY' },
      ],
    },
    {
      source: '/media/:file*',
      headers: [{ key: 'Cache-Control', value: 'public, max-age=600, must-revalidate' }],
    },
  ],
};

export default config;
