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
- Radio Browser station discovery by two-letter country code.
- The supplied horizontal wordmark in the app header and standalone mark for
  native launcher icons.

Native video, lyrics, authentication, crossfade, and the web app's other
advanced player features are not included in this native release.

## Android

Open `android/` in Android Studio, or build from the repository root:

```bash
npm run build:android:debug
npm run build:android
npm run build:android:bundle
```

An Android SDK and JDK 17 are required; the committed Gradle wrapper downloads
the pinned Gradle version. Android's document picker grants access only to
audio files the user selects; MYLO does not request broad media-library
permission. Internet access is used for station discovery and streams. Radio
streams must use HTTPS.

The resulting debug APK is at `android/app/build/outputs/apk/debug/`. Configure
release signing in Android Studio before publishing a release.

## iOS

On macOS with Xcode, open `ios/App/App.xcodeproj`, select the `MYLO` scheme, and
run or archive the app. The project targets iOS 16 and later.

Audio imports are copied into the app's Documents directory. The app declares
the audio background mode for playback. Set a valid development team and
bundle-signing settings in Xcode before running on a physical device or
archiving for the App Store.

## Web / PWA

Serve the repository root over HTTPS (or localhost) to run the independent web
app. Its HTML-based feature set and PWA/service-worker behavior are unchanged by
the native mobile implementation.
