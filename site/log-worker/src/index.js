// Daanvia diagnostics logs.
//
// The app sends its own log (bike display connection, Bluetooth clock, ride messages) here every few minutes and when
// the display drops or a ride ends, so problems can be looked at without the rider copying a report by hand.
// Nothing here identifies a person: each install sends a random id it made itself, the app version and the phone
// model. Logs never hold coordinates, destinations or passwords (the app redacts them before sending).
//
// Routes
//   POST /v1/logs        app upload. Header X-Daanvia-Key (in the app; only keeps out random traffic).
//                        Body JSON { install, version, model, android, text } (text up to 256 KB).
//   GET  /v1/installs    installs seen, newest first.                        Authorization: Bearer ADMIN_TOKEN
//   GET  /v1/logs?install=…&since=…&limit=…  uploads, oldest first, as text. Authorization: Bearer ADMIN_TOKEN
//
// A daily cron deletes uploads older than 30 days.

const APP_KEY = 'dv-logs-7f3c9a2e5b814d06';
const MAX_TEXT = 256 * 1024;
const KEEP_DAYS = 30;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (request.method === 'POST' && url.pathname === '/v1/logs') return await upload(request, env);
      if (request.method === 'GET' && url.pathname === '/v1/installs') return await guarded(request, env, () => installs(env));
      if (request.method === 'GET' && url.pathname === '/v1/logs') return await guarded(request, env, () => logs(url, env));
      return new Response('Not found', { status: 404 });
    } catch (e) {
      return new Response('Error: ' + (e && e.message ? e.message : e), { status: 500 });
    }
  },
  async scheduled(event, env) {
    await env.daanvia_logs.prepare('DELETE FROM uploads WHERE received < ?').bind(Date.now() - KEEP_DAYS * 86400000).run();
  },
};

async function upload(request, env) {
  if (request.headers.get('X-Daanvia-Key') !== APP_KEY) return new Response('Forbidden', { status: 403 });
  const length = Number(request.headers.get('Content-Length') || 0);
  if (length > MAX_TEXT + 4096) return new Response('Too large', { status: 413 });
  let body;
  try { body = await request.json(); } catch { return new Response('Bad JSON', { status: 400 }); }
  const install = str(body.install, 64), text = str(body.text, MAX_TEXT);
  if (!/^[0-9a-f-]{8,64}$/i.test(install) || !text) return new Response('Bad upload', { status: 400 });
  await env.daanvia_logs.prepare('INSERT INTO uploads (install, version, model, android, received, text) VALUES (?, ?, ?, ?, ?, ?)')
    .bind(install, str(body.version, 32), str(body.model, 80), Number(body.android) || 0, Date.now(), text).run();
  return new Response('ok');
}

async function guarded(request, env, then) {
  const auth = request.headers.get('Authorization') || '';
  if (!env.ADMIN_TOKEN || auth !== 'Bearer ' + env.ADMIN_TOKEN) return new Response('Unauthorized', { status: 401 });
  return then();
}

async function installs(env) {
  const { results } = await env.daanvia_logs.prepare(
    'SELECT install, MAX(version) AS version, MAX(model) AS model, MAX(android) AS android, COUNT(*) AS uploads, ' +
    'MIN(received) AS first, MAX(received) AS last, SUM(LENGTH(text)) AS bytes FROM uploads GROUP BY install ORDER BY last DESC LIMIT 200').all();
  return Response.json(results.map(r => ({ ...r, first: new Date(r.first).toISOString(), last: new Date(r.last).toISOString() })));
}

async function logs(url, env) {
  const install = url.searchParams.get('install') || '';
  const since = Date.parse(url.searchParams.get('since') || '') || Date.now() - 2 * 86400000;
  const limit = Math.min(500, Number(url.searchParams.get('limit')) || 100);
  const q = install
    ? env.daanvia_logs.prepare('SELECT * FROM uploads WHERE install = ? AND received >= ? ORDER BY received ASC LIMIT ?').bind(install, since, limit)
    : env.daanvia_logs.prepare('SELECT * FROM uploads WHERE received >= ? ORDER BY received ASC LIMIT ?').bind(since, limit);
  const { results } = await q.all();
  const out = results.map(r => `===== ${new Date(r.received).toISOString()} · ${r.install} · ${r.version} · ${r.model} · Android ${r.android}\n${r.text}`).join('\n');
  return new Response(out || '(no uploads)\n', { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
}

function str(v, max) { return typeof v === 'string' ? v.slice(0, max) : ''; }
