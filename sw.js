/* ==========================================================================
   MYLO service worker — offline app shell, installability, background
   notifications and safe media streaming.

   Must be deployed next to index.html (service workers cannot be inlined).
   Everything here degrades silently if the file is absent.
   ========================================================================== */

/* Bump this on every release — it is what evicts the previous cache. */
const VERSION      = 'v1.0.1';
const SHELL_CACHE  = 'mylo-shell-'  + VERSION;   // html / manifest / offline / icons
const STATIC_CACHE = 'mylo-static-' + VERSION;   // vendor css, fonts
const IMAGE_CACHE  = 'mylo-images-' + VERSION;   // artwork & favicons
const CURRENT      = [SHELL_CACHE, STATIC_CACHE, IMAGE_CACHE];

/* MYLO ships as a single index.html, so the precache list is small and must
   match what actually exists at the deploy root — a 404 here would waste
   install time on every device. */
const SHELL = [
  './',
  './index.html',
  './manifest.webmanifest',
  './offline.html',
  './assets/icons/logo.svg',
  './assets/icons/icon-192.png',
  './assets/icons/icon-512.png',
  './assets/icons/icon-maskable-512.png',
  './PRIVACY.html'
];

/* ---- hosts we never proxy: live media, search APIs, auth -------------- */
const BYPASS = /radio-browser|radio\.|googlevideo|youtube|ytimg|ytstatic|itunes|mzstatic|lrclib|ipapi|supabase|theaudiodb|googleapis|gstatic|fonts\.googleapis\.com/i;
/* note: fonts.gstatic.com is proxied on purpose (font binary caching) */

/* ------------------------------------------------------------------ *
 * install — precache the shell, then take over as soon as we can
 * ------------------------------------------------------------------ */
self.addEventListener('install', event => {
  event.waitUntil((async () => {
    const cache = await caches.open(SHELL_CACHE);
    // addAll() is atomic: one 404 kills the install, so add individually.
    await Promise.all(SHELL.map(url =>
      cache.add(new Request(url, { cache: 'reload' })).catch(() => {})
    ));
    await self.skipWaiting();
  })());
});

/* ------------------------------------------------------------------ *
 * activate — drop old versions, enable navigation preload, claim clients
 * ------------------------------------------------------------------ */
self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter(k => !CURRENT.includes(k)).map(k => caches.delete(k)));

    // Let the browser fetch navigations in parallel with SW start-up —
    // noticeably faster cold launches on slow connections.
    if (self.registration.navigationPreload) {
      try { await self.registration.navigationPreload.enable(); } catch (e) {}
    }
    await self.clients.claim();
  })());
});

/* ------------------------------------------------------------------ *
 * fetch — request-type aware routing
 * ------------------------------------------------------------------ */
self.addEventListener('fetch', event => {
  const req = event.request;

  // Only GETs are cacheable.
  if (req.method !== 'GET') return;

  let url;
  try { url = new URL(req.url); } catch (e) { return; }

  // http(s) only (blob:, data:, chrome-extension: pass straight through)
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return;

  // 1. Never touch ranged requests. Audio seeking/streaming breaks badly
  //    if a SW answers a 206 with a cached full body.
  if (req.headers.has('range')) return;

  // 2. Never touch media elements or live API traffic.
  if (req.destination === 'audio' || req.destination === 'video') return;
  if (BYPASS.test(url.host)) return;

  // 3. Local library blobs.
  if (url.protocol === 'blob:') return;

  event.respondWith(route(event, req, url));
});

async function route(event, req, url) {
  const isSameOrigin = url.origin === self.location.origin;

  /* ---------- navigations: network first, cached shell as fallback ------- */
  if (req.mode === 'navigate') {
    try {
      const preload = event.preloadResponse ? await event.preloadResponse : null;
      const res = preload || await fetchWithTimeout(req, 6000);
      if (res && res.ok) {
        const copy = res.clone();
        caches.open(SHELL_CACHE).then(c => c.put('./index.html', copy)).catch(() => {});
      }
      return res;
    } catch (e) {
      const cache = await caches.open(SHELL_CACHE);
      return (await cache.match('./index.html')) ||
             (await cache.match('./')) ||
             (await cache.match('./offline.html')) ||
             new Response(fallbackHTML(), {
               status: 503,
               headers: { 'Content-Type': 'text/html; charset=utf-8' }
             });
    }
  }

  /* ---------- artwork: cache-first, refresh in background ---------------- */
  if (req.destination === 'image') {
    return staleWhileRevalidate(req, IMAGE_CACHE, 90);
  }

  /* ---------- same-origin app code: cache-first (fast, versioned) -------- */
  if (isSameOrigin) {
    return staleWhileRevalidate(req, SHELL_CACHE, 40);
  }

  /* ---------- vendor css / webfonts: SWR with a long tail --------------- */
  return staleWhileRevalidate(req, STATIC_CACHE, 60);
}

async function staleWhileRevalidate(req, cacheName, maxEntries) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(req, { ignoreSearch: false });

  const network = fetch(req)
    .then(res => {
      // Opaque (cross-origin no-cors) responses are cacheable but useless to
      // inspect; only skip genuinely broken ones.
      if (res && (res.ok || res.type === 'opaque')) {
        cache.put(req, res.clone()).then(() => trimCache(cacheName, maxEntries)).catch(() => {});
      }
      return res;
    })
    .catch(() => null);

  if (cached) return cached;
  const fresh = await network;
  if (fresh) return fresh;

  return new Response('', { status: 504, statusText: 'Offline' });
}

function fetchWithTimeout(req, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout')), ms);
    fetch(req).then(res => { clearTimeout(timer); resolve(res); })
              .catch(err => { clearTimeout(timer); reject(err); });
  });
}

/* keep caches bounded so installs don't grow forever */
async function trimCache(cacheName, maxEntries) {
  try {
    const cache = await caches.open(cacheName);
    const keys = await cache.keys();
    if (keys.length <= maxEntries) return;
    await Promise.all(keys.slice(0, keys.length - maxEntries).map(k => cache.delete(k)));
  } catch (e) {}
}

function fallbackHTML() {
  return `<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MYLO — offline</title><style>
body{margin:0;height:100vh;display:grid;place-items:center;background:#0c0a12;
color:#f4f1fa;font-family:system-ui,-apple-system,sans-serif;text-align:center}
h1{letter-spacing:.2em;font-size:20px;margin:0 0 8px}
p{color:#8b86a0;font-size:13.5px;max-width:320px;line-height:1.6}
b{color:#e60023}
</style></head><body><div>
<h1>MYLO</h1>
<p>You're offline. Your <b>local library</b> and any cached songs still play — reconnect to load radio and videos.</p>
</div></body></html>`;
}

/* ------------------------------------------------------------------ *
 * notifications — so alerts still land while the app is backgrounded
 * ------------------------------------------------------------------ */
const BRAND_ICON = './assets/icons/icon-192.png';

self.addEventListener('push', event => {
  let payload = {};
  try { payload = event.data ? event.data.json() : {}; } catch (e) {
    try { payload = { body: event.data && event.data.text() }; } catch (e2) {}
  }
  const title = payload.title || 'MYLO';
  event.waitUntil(self.registration.showNotification(title, {
    body: payload.body || '',
    icon: payload.icon || BRAND_ICON,
    badge: BRAND_ICON,
    tag: payload.tag || 'mylo-update',       // replaces instead of stacking
    renotify: !!payload.renotify,
    silent: payload.silent !== false,
    data: { url: payload.url || './index.html' }
  }));
});

/* the page can ask the SW to notify when it is hidden (e.g. sleep timer) */
self.addEventListener('message', event => {
  const msg = event.data || {};

  if (msg === 'SKIP_WAITING' || msg.type === 'SKIP_WAITING') {
    self.skipWaiting();
    return;
  }

  if (msg.type === 'NOTIFY') {
    event.waitUntil(self.registration.showNotification(msg.title || 'MYLO', {
      body: msg.body || '',
      icon: msg.icon || BRAND_ICON,
      badge: BRAND_ICON,
      tag: msg.tag || 'mylo',
      silent: true,
      data: { url: msg.url || './index.html' }
    }));
    return;
  }

  if (msg.type === 'PURGE_CACHES') {
    event.waitUntil(caches.keys().then(keys =>
      Promise.all(keys.map(k => caches.delete(k)))
    ));
  }
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const target = (event.notification.data && event.notification.data.url) || './index.html';
  const action = event.action || 'open';

  event.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });

    // Reuse an open tab instead of piling up new ones.
    for (const client of all) {
      if (client.url.includes(self.location.origin)) {
        // Forward transport actions to the app so they work from the tray.
        if (action !== 'open') {
          client.postMessage({ type: 'NOTIFICATION_ACTION', action });
        }
        await client.focus();
        return;
      }
    }
    return self.clients.openWindow(target);
  })());
});

/* ------------------------------------------------------------------ *
 * background sync — resume library imports / metadata enrichment
 * ------------------------------------------------------------------ */
self.addEventListener('sync', event => {
  if (event.tag !== 'mylo-sync') return;
  event.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    all.forEach(client => client.postMessage({ type: 'BACKGROUND_SYNC' }));
  })());
});
