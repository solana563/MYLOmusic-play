# MYLO

**Radio · Video · Your Music** — one local-first player that unifies worldwide
internet radio, YouTube and your own local audio files.

Ships as a **PWA** and as a real **native Android & iOS app** via Capacitor.

<p align="center">
  <img src="assets/icons/logo.svg" alt="MYLO" width="220">
</p>

---

## Quick start (web)

```bash
npx serve .            # serves the repo root on http://localhost:3000
```

That's it — MYLO has **no build step and no bundler**. `index.html` is the
entire application.

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

---

## Project layout

```
.
├── index.html                 ← the entire app (CSS + JS inline)
├── manifest.webmanifest       PWA manifest (static)
├── sw.js                      service worker: offline shell, notifications, background sync
├── offline.html / 404.html    offline + not-found fallbacks
├── PRIVACY.html               privacy policy (required by both app stores)
├── PUBLISHING.md              ← full deploy + store submission checklist
├── CREDITS.md                 services, licences, YouTube/Supabase obligations
├── _headers  vercel.json  netlify.toml  robots.txt  sitemap.xml
├── tools/make-icons.html      renders the real PNG icons (run once)
├── assets/icons/              logo.svg + generated icon-*.png
├── android/  ios/             native projects (Capacitor shells + customised sources)
└── capacitor.config.ts  package.json
```

---

## Native builds

```bash
npm install
npm run add:android        # or add:ios
npm run icons              # or: open tools/make-icons.html first (§2 of PUBLISHING.md)
npm run assets             # adaptive icons + splash from resources/icon.png
npm run sync
npm run build:android:aab  # Play Store bundle
npm run open:ios           # Xcode → Product ▸ Archive
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

**Rendering.** Lazy per-tab rendering plus 60-at-a-time pagination in the
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

---

## Contributing notes

- Bump **all three** version strings before a release (see PUBLISHING.md §0).
- Never commit `android/keystore.properties` or `*.jks` — `.gitignore` covers it.
- Keep `_headers` (Netlify/Cloudflare) and `vercel.json` in sync when adding hosts.
- Adding a host? Update both the CSP in those files and `BYPASS` in `sw.js`.

## Licence

Application code © MYLO. Third-party services and assets are attributed in
[CREDITS.md](CREDITS.md) — review it before publishing commercially.
