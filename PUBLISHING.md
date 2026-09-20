# MYLO — publishing checklist

Everything needed to ship MYLO to the web, Google Play and the App Store.
Work top to bottom; each section is independent.

---

## 0 · Version bump (every release)

Keep these three in sync or the service worker will serve a stale shell:

| Where | What |
|---|---|
| `index.html` → `APP = { VERSION, BUILD }` | shown in onboarding + profile |
| `sw.js` → `const VERSION = 'v1.0.1'` | **bumping this evicts the old cache** |
| `android/app/build.gradle` → `versionCode` / `versionName` | Play Store |
| `ios/App/App/Info.plist` → `CFBundleVersion` / `CFBundleShortVersionString` | App Store |

---

## 1 · Deploy files

Upload **all of these** to the deploy root (they are all in this repo):

```
index.html                  the entire app
sw.js                       offline shell + background notifications
manifest.webmanifest        PWA manifest (static, not JS-generated)
offline.html                shown when a navigation fails offline
404.html                    not-found page
robots.txt  sitemap.xml     SEO
_headers                    Netlify / Cloudflare headers  ─┐ pick the one for
vercel.json                 Vercel headers                 ─┘ your host
PRIVACY.html                required by both app stores
CREDITS.md                  service attribution (keep public too)
tools/make-icons.html       internal tool — safe to exclude from the deploy
assets/icons/logo.svg       wordmark
assets/icons/icon-192.png   ← generate these first, see §2
assets/icons/icon-512.png
assets/icons/icon-maskable-512.png
```

Update the placeholder domain `https://mylo.app` in `robots.txt`, `sitemap.xml`
and the social-preview tags in `index.html` to your real origin.

---

## 2 · Generate the real icons (do not skip)

Open **`tools/make-icons.html`** in a browser and download the four PNGs it
renders. They are rasterised from the official wordmark via canvas, because
Chrome's installability check rejects SVG manifest icons.

Place them:

```
assets/icons/icon-192.png            192×192   any
assets/icons/icon-512.png            512×512   any
assets/icons/icon-maskable-512.png   512×512   maskable (60% inset)
resources/icon.png                   1024×1024 native source
```

> If the PNGs are missing, MYLO silently falls back to generating them at
> runtime — so nothing breaks, but install prompts get flakier. Ship the files.

---

## 3 · Web / PWA

```bash
npx serve .          # or any static host
```

Verify in DevTools → **Application**:

- [ ] Manifest parses, shows the MYLO name + all three icons
- [ ] Service worker **activated and running**
- [ ] Under **Cache Storage**: `mylo-shell-*`, `mylo-static-*`, `mylo-images-*`
- [ ] Install icon appears in the address bar
- [ ] **Shortcuts** list Radio + Library on long-press of the home-screen icon
- [ ] Go offline → app still opens (shell cached), local songs still play

**HTTPS is mandatory** — service workers and the install prompt are refused on
plain HTTP. `localhost` is exempt for testing.

---

## 4 · Configure the backends

### Supabase (auth only)
Authentication → **URL Configuration**:
- Site URL: `https://your-domain`
- Redirect URLs: `https://your-domain/**` **and** `mylo://auth-callback`
- Providers → enable Google (paste the OAuth client ID/secret)

Row Level Security: **on** for every table. MYLO stores no server rows.

### Google Cloud (YouTube)
APIs & Services → Credentials → restrict the YouTube Data API v3 key:
- Android: package `app.mylo.music` + SHA-1 of your signing cert
- iOS: bundle ID `app.mylo.music`
- Web: HTTP referrer `https://your-domain/*`

---

## 5 · Android (Google Play)

```bash
npm install
npm run add:android        # scaffolds Gradle around the committed sources
npm run assets             # adaptive icons + splash screens
npm run sync
npm run build:android:aab  # → android/app/build/outputs/bundle/release
```

### Release signing (once)
```bash
keytool -genkey -v -keystore mylo-release.jks -keyalg RSA \
        -keysize 2048 -validity 10000 -alias mylo
```
Add `android/keystore.properties` (git-ignored), reference it from
`android/app/build.gradle` in `signingConfigs.release`, and set
`buildTypes.release.signingConfig signingConfigs.release`.
**Back the keystore up — you cannot update the app without it.**

### Play Console
| Field | Value |
|---|---|
| App name | MYLO — Radio · Video · Your Music |
| Package | `app.mylo.music` |
| Category | Music & Audio |
| Content rating | complete the questionnaire (third-party content, user-selected) |
| Data safety | Collects **no** data. Email (if provided) is processed by Supabase for auth only. |
| Privacy policy URL | your hosted `PRIVACY.html` |
| Permissions declared | Internet, network state, `READ_MEDIA_AUDIO` (import own files), foreground service media playback |
| Upload | the `.aab`, then complete the internal → closed → production track |

---

## 6 · iOS (App Store)

```bash
npm run add:ios
npm run assets
npm run sync
npm run build:ios          # opens Xcode → Product ▸ Archive
```

- Signing: your Team, automatic signing, a unique bundle ID.
- `Info.plist` already declares: background **audio** mode, `UIBackgroundModes`,
  the `mylo://` URL scheme, `ITSAppUsesNonExemptEncryption = false`, and
  purpose strings for media-library access.
- App Privacy: "Data Not Collected" except **Email Address → App Functionality**
  (auth). Mark it **not used for tracking**.
- Age rating: 12+ (unrestricted web/video content supplied by third parties).
- Add the same privacy policy URL.

### Screenshots to capture
| Size | Where |
|---|---|
| 6.7" iPhone (1290×2796) | Listen Now, Now Playing, Lyrics |
| 6.5" iPhone (1242×2688) | Radio, Library |
| 12.9" iPad (2048×2732) | Library grid, Now Playing (landscape) |
| 7" / 10" Android | Radio, Videos, Lyrics |

---

## 7 · Pre-flight smoke test

Run through this on a **real device**, not just a desktop viewport:

- [ ] Onboarding: sign in, sign up, magic link, Google, **and guest**
- [ ] Sign out of a gated feature → Radio/Lyrics re-lock correctly
- [ ] Gapless: play an album and confirm no gap between tracks
- [ ] Crossfade on 3s / 5s / 8s, then skip mid-fade → no double-advance, no silence
- [ ] Waveform scrub while playing audio **and** while playing video
- [ ] Lyrics: synced highlight tracks the audio; tap a line → seeks; change track → re-resolves
- [ ] Radio: country detection, genre chips, bookmark to Library → Stations
- [ ] Library: import files, edit metadata, re-fetch artwork, delete
- [ ] Playlists: mix a local track and a video, play each sub-queue
- [ ] Background the app mid-song → audio continues, lock-screen controls work
- [ ] Lock the phone → notification shows the right art/title, next/prev work
- [ ] Rotate to landscape → Now Playing switches to side-by-side art + controls
- [ ] Airplane mode → offline shell loads, local files play, radio degrades gracefully

---

## 8 · Known, honest limitations

These are real browser/platform restrictions, not unfinished work:

- **Single-file inline CSS/JS** means the CSP needs `'unsafe-inline'`. Split the
  bundle if your security review demands nonces.
- **YouTube video** has no visualizer and no Media Session integration — the
  IFrame is sandboxed and exposes no raw audio to the host page. The bars shown
  for video are a playback animation, labelled as such in the UI.
- **Offline** covers the app shell and local files. Radio and videos need a
  network by definition.
- **`ipapi.co`** free tier is 1k requests/day. The code fails soft (manual
  country picker) but a busy deploy should proxy it.
