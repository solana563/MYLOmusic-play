# MYLO

**Radio · Video · Your Music** — one local-first player that unifies worldwide
internet radio, YouTube and your own local audio files.

Ships as a **PWA** and as separate **native Android (Java)** and **iOS (SwiftUI)**
apps. The mobile apps do not embed or load the web app.

<p align="center">
  <img src="assets/icons/logo.svg" alt="MYLO" width="220">
</p>

---

## Quick start (web)

```bash
npm ci
npm run build          # production static output in public/
npx serve public       # serves the built app on http://localhost:3000
```

The web app has no bundler or framework. The build copies its static files,
generates the PWA PNG icons from the standalone SVG, and writes
`runtime-config.js` from optional public-client environment variables. For
source editing, `npx serve .` still serves the repository root directly.

### Deploy to Vercel

Import this repository in Vercel and keep the project root at the repository
root. The checked-in `vercel.json` runs `npm run build` and publishes `public/`.
Set the following Vercel project environment variables when those services are
configured:

| Variable | Purpose |
|---|---|
| `MYLO_SUPABASE_URL` | Supabase project URL; configure together with the anon key |
| `MYLO_SUPABASE_ANON_KEY` | Browser-safe Supabase anon/publishable key, never a service-role key |
| `MYLO_YOUTUBE_API_KEY` | YouTube Data API key; restrict it to the deployed HTTPS origins |
| `MYLO_SITE_URL` | Optional canonical HTTPS origin for sitemap and social-preview URLs |

The Supabase URL and anon key must either both be set or both be omitted.
Without service credentials, guest/local features and curated video content
remain available; Supabase sign-in and YouTube search require their respective
configuration. These values are included in browser-delivered JavaScript and
are **not secrets**; never put a Supabase service-role key in Vercel's web
configuration. For a first Vercel deploy, the deployment URL is used for
metadata if `MYLO_SITE_URL` is not set.

> Service workers, the install prompt and background notifications only work
> over `http(s)`. Opening `index.html` from `file://` runs the app but disables
> those (by browser design, not by omission).

### Hero features

| | |
|---|---|
| **Radio** | Worldwide stations (radio-browser.info), genre chips, IP-based local radio chosen from 14 quick countries |
| **Videos** | YouTube Data API v3 search + trending with a curated fallback list, played via the IFrame Player API |
| **Your library** | Import local files (drag-and-drop), ID3/MP4 tags read on-device, artwork downscaled to 260px JPEG before IndexedDB |
| **Player** | Dual-buffer **gapless** playback, opt-in **crossfade** (3/5/8s) on real Web Audio gain ramps, shuffle/repeat that re-prime the preload |
| **Lyrics** | A dedicated screen with **synced karaoke highlighting** (lrclib.net), scored track matching, tap-a-line to seek |
| **Now Playing** | Live waveform scrubber, Web Audio visualizer, adaptive album-art colour, Media Session lock-screen controls |
| **Auth** | Supabase email/password, magic link, Google OAuth. Radio + Lyrics are members-only; everything else works as a guest. |

The feature list above describes the **web/PWA**. The first native mobile release
includes local music import and playback, radio streaming, and on-device
playlists. Native video, lyrics, and sign-in are not included yet.

---

## Project layout

```
.
├── index.html                 ← the complete web/PWA app (CSS + JS inline)
├── manifest.webmanifest       PWA manifest (static)
├── sw.js                      service worker: offline shell, notifications, background sync
├── offline.html / 404.html    offline + not-found fallbacks
├── PRIVACY.html               privacy policy (required by both app stores)
├── PUBLISHING.md              ← full deploy + store submission checklist
├── CREDITS.md                 services, licences, YouTube/Supabase obligations
├── _headers  vercel.json  netlify.toml  robots.txt
├── tools/make-icons.html      optional app-icon preview/download utility
├── assets/icons/              logo.svg wordmark, logo-mark.svg standalone mark + app icons
├── android/                   native Android app (Java + Android SDK)
├── ios/App/App.xcodeproj/      native iOS app (SwiftUI + AVFoundation)
├── scripts/build-web.js       production static export + PWA icons
└── package.json
```

---

## Native builds

```bash
npm run build:android:debug # Android SDK + JDK 17 required
npm run build:android:bundle
npm run open:ios            # macOS + Xcode required
```

See **[PUBLISHING.md](PUBLISHING.md)** for signing, store listings, permission
rationale and the pre-flight device test.

---

## Architecture notes

**Player engine.** Two real `<audio>` elements. The next track is silently
preloaded into the standby element and swapped on `ended` for true gapless
playback. With crossfade on, the standby starts early and both elements'
`GainNode`s ramp across the fade window; a manual skip cancels and resets
cleanly. Toggling shuffle/repeat re-primes the preload so the change applies to
the very next auto-advance.

**Local-first.** IndexedDB holds tracks, blobs and downscaled artwork (the
single biggest fix for large-library performance). `localStorage` holds likes,
playlists, saved stations and settings. Supabase is **authentication only** —
no user content leaves the device.

**Web rendering.** Lazy per-tab rendering plus 60-at-a-time pagination in the
Library; list and grid images lazy-load and fade in. No runtime CSS engine:
Tailwind's CDN JIT was deliberately removed in favour of a hand-authored
stylesheet verified class-by-class against real usage.

**Live-following lyrics.** A small track-change bus tells the lyrics screen when
the song actually changes (manual skip, crossfade, gapless advance, radio ↔
video), then the resolver cleans noisy titles, tries several lrclib endpoints
and scores candidates on title, artist, album **and duration** before picking
one. Stale in-flight requests are discarded.

**Background & notifications.** Media Session handlers are bound once and read
live state, so lock-screen, Bluetooth and car controls keep working. A Screen
Wake Lock prevents mid-song throttling, the AudioContext is re-resumed on
return to foreground, playback position is snapshotted for tab-eviction
recovery, and the service worker can raise OS notifications while the app is
hidden.

**Native mobile.** The Android and iOS apps use platform-native UI and media
players. They persist imported audio references/files and playlist membership on
device, and load public radio stations from Radio Browser over HTTPS. The web
app remains independent and continues to provide the full PWA feature set.

---

## Contributing notes

- Bump **all three** version strings before a release (see PUBLISHING.md §0).
- Never commit `android/keystore.properties` or `*.jks` — `.gitignore` covers it.
- Keep `_headers` (Netlify/Cloudflare) and `vercel.json` in sync when adding hosts.
- Adding a host? Update both the CSP in those files and `BYPASS` in `sw.js`.

## Licence

Application code © MYLO. Third-party services and assets are attributed in
[CREDITS.md](CREDITS.md) — review it before publishing commercially.
