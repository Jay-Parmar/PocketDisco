# Research sources

Checked 2026-08-16. Official/primary documentation was preferred. Legal documents themselves are authoritative; this planning material is not legal advice.

## Spotify

- [Spotify Developer Policy (effective 15 May 2025)](https://developer.spotify.com/policy) — prohibited applications, group/broadcast example, provider mixing, Premium, and commercial restrictions.
- [Spotify Android SDK](https://developer.spotify.com/documentation/android) — App Remote capabilities and architecture.
- [Android SDK getting started](https://developer.spotify.com/documentation/android/tutorials/getting-started) — playlist playback, PlayerState, installation, and policy notes.
- [Spotify quota modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes) — five-user development mode and current extended-quota application requirements.
- [February 2026 development-mode migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide) — 2026 account/app limits and timing.
- [Seek to position reference](https://developer.spotify.com/documentation/web-api/reference/seek-to-position-in-currently-playing-track) — Premium, playback control, and non-guaranteed endpoint ordering.
- [Spotify rate limits](https://developer.spotify.com/documentation/web-api/concepts/rate-limits) — rolling-window behavior and 429 responses.

## YouTube

- [YouTube IFrame Player API](https://developers.google.com/youtube/iframe_api_reference) — play/pause/seek/time, playlist operations, Android WebView integrity, autoplay-blocked event.
- [YouTube on Android](https://developers.google.com/youtube/android) — IFrame API is the official Android playback route; the older Android Player API is no longer available.
- [Required minimum functionality](https://developers.google.com/youtube/terms/required-minimum-functionality) — WebView type, client identity/referrer, player size, autoplay visibility, overlays.
- [Developer policy guide](https://developers.google.com/youtube/terms/developer-policies-guide) — standard player experience, ads, attribution/metadata, no audio extraction, no background playback.
- [YouTube Data API](https://developers.google.com/youtube/v3/docs) — official videos/playlists metadata surface.
- [Embedded player parameters](https://developers.google.com/youtube/player_parameters) — playlist IDs and supported player configuration.

## Apple

- [Apple Developer Program License Agreement](https://developer.apple.com/support/terms/apple-developer-program-license-agreement/) — MusicKit user initiation, standard controls, content handling, and monetization restrictions.
- [MusicKit overview](https://developer.apple.com/documentation/musickit) — catalog access and playback support.
- [MusicKit user authentication](https://developer.apple.com/documentation/applemusicapi/user-authentication-for-musickit) — Android Music User Token handling.
- [MusicKit for Android API overview](https://developer.apple.com/musickit/android/overview-summary.html) — Android authentication/playback packages.

## Architecture

- [Android Media3 basic playback](https://developer.android.com/media/implement/playback-app) — prepare, play/pause/seek, lifecycle, and release.
- [Media3 preload manager](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager) — preloading media for faster starts.
- [Media3 troubleshooting](https://developer.android.com/media/media3/exoplayer/troubleshooting) — seeking limitations, especially VBR MP3, and background playback considerations.
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback) — `MediaSessionService` and foreground-service permissions/lifecycle.
- [Android `SystemClock`](https://developer.android.com/reference/android/os/SystemClock) — monotonic elapsed-realtime clock used for interval/scheduling calculations.
- [FastAPI WebSockets](https://fastapi.tiangolo.com/advanced/websockets/) — framework WebSocket support.
- [Redis Pub/Sub delivery semantics](https://redis.io/docs/latest/develop/pubsub/) — at-most-once behavior and recommendation to use Streams for stronger guarantees.
- [Redis Streams](https://redis.io/docs/latest/develop/data-types/streams/) — append-only replay/consumer semantics.
- [React Native Windows](https://microsoft.github.io/react-native-windows/) — Windows React Native support.
- [React Native Windows native modules](https://microsoft.github.io/react-native-windows/docs/native-platform-modules/) — TypeScript spec/codegen and native implementations.
