# MYLO — Native Build (Capacitor)

MYLO ships as a single `index.html` **and** a real installable native app for
Android + iOS via [Capacitor](https://capacitorjs.com). The web app already
detects the native runtime (`window.Capacitor.isNativePlatform()`) and wires up
status bar, splash screen, hardware back button and `mylo://` deep links at
runtime — see `initNative()` in `index.html`.

## One-time setup

```bash
npm install
npm run add:android      # creates the native Android project
npm run add:ios          # creates the native iOS project (macOS + Xcode)
```

`prepare:web` copies `index.html` (and `sw.js`) into `www/`, which is Capacitor's
`webDir`. Re-run `npm run sync` after any change to the HTML.

## Branded icons & splash (adaptive layers)

Drop a 1024×1024 `resources/icon.png` and `resources/splash.png`, then:

```bash
npm run icons
```

`@capacitor/assets` generates proper Android **adaptive-icon** foreground/background
layers, round icons, and iOS icon sets + splash screens — all on the `#0c0a12`
brand background with the red/violet MYLO mark.

## Deep links (OAuth + magic link)

`capacitor.config.json` registers the `mylo` URL scheme. Google OAuth and
Supabase magic links redirect to **`mylo://auth-callback`**; the native shell
catches it via the `appUrlOpen` listener and completes the session **without
navigating the WebView away**, then returns to the app. On the web build the same
flow falls back to a plain same-origin redirect.

Android also needs an intent filter (added automatically by `npm run add:android`,
or add manually to `AndroidManifest.xml`):

```xml
<intent-filter>
  <action android:name="android.intent.action.VIEW"/>
  <category android:name="android.intent.category.DEFAULT"/>
  <category android:name="android.intent.category.BROWSABLE"/>
  <data android:scheme="mylo" android:host="auth-callback"/>
</intent-filter>
```

## Run on a device

```bash
npm run run:android
npm run run:ios
```

## What's native vs. what stays web

| Subsystem | Native integration |
|---|---|
| Status bar | Colour + icon style follow the current song's album art (`nativeStatusBar`) |
| Back button | Closes sheets → returns to Home → exits app |
| Splash screen | Branded, auto-hidden once the UI paints |
| Deep links | `mylo://auth-callback` for OAuth / magic link |
| Keyboard | Native resize mode |
| Audio, IndexedDB library, Web Audio graph, Media Session | Run inside the WebView exactly as on web |

> A ground-up Kotlin/Swift rewrite would reimplement every subsystem against
> native APIs. The Capacitor wrapper gives native packaging, install, icons,
> splash and back-button behaviour **without** that rewrite.

## PWA (web install)

`sw.js` must be served next to `index.html` for Chrome's desktop install prompt
and offline app-shell caching. Service workers cannot be inlined into a single
HTML file — a real browser restriction — so it's a separate, opt-in file that is
silently skipped if absent.
