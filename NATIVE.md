# MYLO native mobile apps

The mobile apps are platform-native and do not use a WebView, HTML, or the
Capacitor runtime. The existing `index.html` remains the separate web/PWA app.

## Native feature scope

The initial native apps provide:

- Native listen, radio, library, and playlist screens.
- Audio-file import using Android's Storage Access Framework and iOS's document
  importer.
- On-device library and playlist persistence.
- Local-file playback and live radio playback using native media players.
- Supabase email/password accounts, magic-link sign-in, and Google OAuth.
- Refresh-token persistence protected by Android Keystore or the iOS Keychain.
- Radio Browser station discovery by two-letter country code.
- The supplied horizontal wordmark in the app header and standalone mark for
  native launcher icons.

Native video, lyrics, crossfade, and the web app's other advanced player
features are not included in this native release.

## Android

Open `android/` in Android Studio, or build from the repository root:

```bash
npm run build:android:debug
npm run build:android
npm run build:android:bundle
```

Android SDK Platform 36, Build Tools, and JDK 17 are required; the committed
Gradle wrapper downloads the pinned Gradle version. The app targets API 36 for
current Google Play submissions. Android's document picker grants access only
to audio files the user selects; MYLO does not request broad media-library
permission. Internet access is used for station discovery and streams. Radio
streams must use HTTPS.

Configure `MYLO_SUPABASE_URL` and `MYLO_SUPABASE_ANON_KEY` as Gradle properties
or environment variables when building. Add `mylo://auth-callback` to the
Supabase Auth redirect URL allow-list and enable Google in Supabase if using
Google sign-in.

The resulting debug APK is at `android/app/build/outputs/apk/debug/`. Configure
release signing before building a store bundle. Supply either
`android/keystore.properties` (see `PUBLISHING.md`) or the documented
`MYLO_*` environment variables. Release APK/AAB tasks intentionally fail when
upload-key signing is not configured.

## iOS

On macOS with Xcode, open `ios/App/App.xcodeproj`, select the `MYLO` scheme, and
run or archive the app. The project targets iOS 16 and later. The root package
provides `npm run build:ios:simulator` and `npm run archive:ios` commands.
Those commands pass `MYLO_SUPABASE_URL` and `MYLO_SUPABASE_ANON_KEY` from the
environment to Xcode. If building directly from Xcode, set those values in the
target's Build Settings. Add `mylo://auth-callback` to Supabase Auth's redirect
URL allow-list.

Audio imports are copied into the app's Documents directory. The app declares
the audio background mode for playback. Set a valid development team and
bundle-signing settings in Xcode before running on a physical device or
archiving for the App Store.

## Web / PWA

Serve the repository root over HTTPS (or localhost) to run the independent web
app. Its HTML-based feature set and PWA/service-worker behavior are unchanged by
the native mobile implementation.
