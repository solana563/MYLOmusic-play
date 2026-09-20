# MYLO — services, licences & attribution

MYLO is a client-side application. It stores no user content on a server and
relies on the following third-party, free-to-use services. Each has its own
terms; **review them before publishing commercially.**

## Data & media sources

| Service | Used for | Key required | Terms |
|---|---|---|---|
| [radio-browser.info](https://www.radio-browser.info/) | Worldwide station directory, tags, stream URLs | No | Community DB, public domain data |
| [iTunes Search API](https://performance-partners.apple.com/search-api) | Song/artist metadata, artwork, 30s previews | No | Apple Search API terms |
| [TheAudioDB](https://www.theaudiodb.com/api_guide.php) | Artist biographies, photos, genre/mood tags | Public test key `2` | Free tier; request a real key for production |
| [YouTube Data API v3](https://developers.google.com/youtube/v3) | Trending, search, video metadata | **Yes** (configured) | YouTube API Services Terms — see below |
| [YouTube IFrame Player API](https://developers.google.com/youtube/iframe_api_reference) | Video playback | No | YouTube ToS + branding rules |
| [lrclib.net](https://lrclib.net/) | Synced (LRC) and plain lyrics | No | Free public API |
| [ipapi.co](https://ipapi.co/) | IP → country for "Local radio" | No | Free tier 1k/day; graceful fallback if exceeded |
| [MusicBrainz](https://musicbrainz.org/doc/MusicBrainz_API) | Release lookup (artwork fallback) | No | CC0 data; **1 req/sec** rate limit |
| [Cover Art Archive](https://coverartarchive.org/) | Front cover images | No | Per-release licensing |
| [jsmediatags](https://github.com/aadsm/jsmediatags) | Client-side ID3/MP4 tag parsing | No | LGPL-3.0 |

## Services

| Service | Used for |
|---|---|
| [Supabase](https://supabase.com/) | **Authentication only** — email/password, magic link, Google OAuth. No user content is stored server-side. |
| [Google Fonts](https://fonts.google.com/) | Inter typeface |
| [Font Awesome](https://fontawesome.com/) | Icons (free tier, CC BY 4.0 + SIL OFL 1.1) |
| [Capacitor](https://capacitorjs.com/) | Native Android + iOS packaging (MIT) |

## YouTube API Services — publisher obligations

Because MYLO embeds YouTube, publishing it binds you to the
[YouTube API Services Terms of Service](https://developers.google.com/youtube/terms/api-services-terms-of-service).
At minimum:

1. **Link to the YouTube ToS and Google Privacy Policy** somewhere user-visible.
   `PRIVACY.html` already does this — keep it deployed and reachable.
2. **Restrict your API key** in Google Cloud Console:
   - Android: package name + SHA-1 signing certificate
   - iOS: bundle ID
   - Web: HTTP referrer
3. **Do not** strip, obscure or alter YouTube branding, and never display video
   outside the IFrame player (MYLO does not).
4. Users must be able to delete their data — covered locally, and via the
   support address in the privacy policy.

## Supabase — publisher obligations

Set the Supabase **Site URL** and **Redirect URLs** to your production origin,
plus the `mylo://auth-callback` deep link used by the Capacitor shell
(Authentication → URL Configuration). Keep **Row Level Security enabled** on
every table. MYLO stores no rows, so RLS can deny everything by default.

Enable the Google provider under Authentication → Providers, and paste the
Google OAuth client ID/secret from the same Cloud project whose key you
restricted above.

## Fonts & icons

- **Inter** — SIL Open Font License 1.1 (Google Fonts).
- **Font Awesome Free** — icons CC BY 4.0, fonts SIL OFL 1.1, code MIT.
- **MYLO wordmark** — © MYLO. Supplied as `assets/icons/logo.svg`.

## Rate-limit etiquette

MYLO caches every API response in memory for the session and:
- never polls radio-browser faster than one request per view,
- batches iTunes lookups,
- falls back to a curated video list if the YouTube key fails,
- throttles MusicBrainz to a single release lookup per artwork miss.

If you scale traffic significantly, host your own [radio-browser mirror](https://api.radio-browser.info/)
and register for a production TheAudioDB key.
