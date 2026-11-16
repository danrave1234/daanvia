/**
 * The site's public address: the custom domain once it is set up (NEXT_PUBLIC_SITE_URL in the Vercel project), the
 * Vercel address until then. Used for the canonical link, social previews, robots.txt and the sitemap.
 */
export const SITE_URL = (process.env.NEXT_PUBLIC_SITE_URL || 'https://daanvia-app.vercel.app').replace(/\/+$/, '');
