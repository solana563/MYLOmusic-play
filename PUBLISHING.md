# MYLO — publishing checklist

Everything needed to ship MYLO to the web, Google Play and the App Store.
Work top to bottom; each section is independent.

---

## 0 · Version bump (every release)

Keep these three in sync or the service worker will serve a stale shell:

| Where | What |
|---|---|
| `index.html` → `APP = { VERSION, BUILD }` | shown in onboarding + profile |
| `sw.js` → `const VERSION` | **bumping this evicts the old cache** |
| Android Gradle properties `MYLO_VERSION_CODE` / `MYLO_VERSION_NAME` (defaults in `android/app/build.gradle`) | Play Store |
| `ios/App/App.xcodeproj/project.pbxproj` → `CURRENT_PROJECT_VERSION` / `MARKETING_VERSION` | App Store |

---

## 1 · Build and deploy the web app

Run `npm ci` followed by `npm run build`. The build creates a complete static
deploy root in `public/`, including PNG app icons generated from the standalone
SVG logo and a `runtime-config.js` file generated from public-client
environment variables. Do not publish the repository root directly.

Vercel reads the build command, `public` output directory and response headers from
`vercel.json`. Import the repository with the project root set to the
repository root. Netlify uses `netlify.toml`; other static hosts can publish
`public/` and apply the response headers in the included `_headers` file.

Set these Vercel build environment variables as applicable:

- `MYLO_SUPABASE_URL` and `MYLO_SUPABASE_ANON_KEY` together to enable auth.
- `MYLO_YOUTUBE_API_KEY` to enable YouTube search and trending.
- `MYLO_SITE_URL` to set the canonical HTTPS site origin for sitemap and social
  preview metadata. When omitted, the Vercel or Netlify deployment URL is used
  when provided by that platform.

These browser-delivered values are public. Use only a Supabase
anon/publishable key (never a service-role key), and restrict the YouTube API
key to the required API and HTTPS referrers. Configure the final site and
callback URLs in Supabase before enabling sign-in.

## 2 · Web / PWA

```bash
npm ci
npm run build
npx serve public     # or publish public/ to a static host
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

## 3 · Configure the backends

### Supabase (web/PWA auth only)
Authentication → **URL Configuration**:
- Site URL: `https://your-domain`
- Redirect URLs: `https://your-domain/**`
- Providers → enable Google (paste the OAuth client ID/secret)

Native mobile builds do not include authentication. Row Level Security should
remain **on** for every table.

### Google Cloud (YouTube, web/PWA only)
APIs & Services → Credentials → restrict the YouTube Data API v3 key:
- Web: HTTP referrer `https://your-domain/*`

---

## 4 · Android (Google Play)

```bash
npm run build:android:debug # Android SDK required; Gradle wrapper is included
npm run build:android:bundle # → android/app/build/outputs/bundle/release
```

Use JDK 17 and install Android SDK Platform 36 and Build Tools. The app targets
API 36 to meet current Google Play target API requirements.

### Release signing (once)
```bash
keytool -genkey -v -keystore mylo-release.jks -keyalg RSA \
        -keysize 2048 -validity 10000 -alias mylo
```
Create `android/keystore.properties` (git-ignored) with these values:
```properties
storeFile=/absolute/path/to/mylo-release.jks
storePassword=your-upload-key-store-password
keyAlias=mylo
keyPassword=your-upload-key-password
```
Alternatively set `MYLO_STORE_FILE`, `MYLO_STORE_PASSWORD`, `MYLO_KEY_ALIAS`,
and `MYLO_KEY_PASSWORD` in the build environment. Release APK/AAB tasks fail
if signing is not configured. Set release versions with Gradle properties
`MYLO_VERSION_CODE` and `MYLO_VERSION_NAME`.
**Back the keystore up — you cannot update the app without it.**

### Play Console
| Field | Value |
|---|---|
| App name | MYLO — Radio · Your Music |
| Package | `com.mylo.music` |
| Category | Music & Audio |
| Content rating | complete the questionnaire (third-party content, user-selected) |
| Data safety | Review the final build and store disclosures before release. |
| Privacy policy URL | your hosted `PRIVACY.html` |
| Permissions declared | Internet. Audio is selected through Android's document picker. |
| Upload | the `.aab`, then complete the internal → closed → production track |

---

## 5 · iOS (App Store)

```bash
npm run build:ios:simulator
npm run archive:ios
```

- Run these commands on macOS with Xcode installed; configure your Team and
  signing settings in Xcode before archiving.
- The target bundle ID is `com.mylo.music`; change it if your store listing uses
  a different identifier.
- Bump `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` in the Xcode project
  for each release.
- The app declares background **audio** mode and imports user-selected audio
  through the document picker; it does not request full Apple Music library
  access.
- Add the same privacy policy URL.

### Screenshots to capture
| Size | Where |
|---|---|
| iPhone and Android phone | Listen, Radio, Library, Playlists |
| iPad and Android tablet | Radio, Library, Playlists |

---

## 6 · Pre-flight smoke test

Run through these checks on **real devices**:

- [ ] Android and iOS launch into native screens with no WebView.
- [ ] Import one and multiple audio files; imported files remain available after restart.
- [ ] Play imported audio and pause/resume it.
- [ ] Load country radio stations, play a secure HTTPS stream, and handle offline/network errors.
- [ ] Create playlists, add library tracks, and verify membership after restart.
- [ ] Web/PWA: verify the shell loads offline and local files remain playable.

---

## 7 · Known, honest limitations

The codebase includes native app projects and build commands, but a store
submission is not complete until signed builds have been produced and tested
on real devices and the publisher has supplied store accounts, signing
identities, screenshots, and completed privacy/data-safety disclosures. Replace
the publisher/support-contact placeholder in `PRIVACY.html` before publishing.

These are real browser/platform restrictions, not unfinished work:

- **Native feature scope:** video, lyrics, authentication, crossfade, and advanced
  player controls remain available only in the web/PWA app.
- **Radio:** native playback supports HTTPS streams; stations offering only
  insecure HTTP streams cannot be played in the native apps.
- **Single-file inline CSS/JS** means the CSP needs `'unsafe-inline'`. Split the
  bundle if your security review demands nonces.
- **YouTube video** has no visualizer and no Media Session integration — the
  IFrame is sandboxed and exposes no raw audio to the host page. The bars shown
  for video are a playback animation, labelled as such in the UI.
- **Offline** covers the app shell and local files. Radio and videos need a
  network by definition.
- **`ipapi.co`** free tier is 1k requests/day. The code fails soft (manual
  country picker) but a busy deploy should proxy it.
