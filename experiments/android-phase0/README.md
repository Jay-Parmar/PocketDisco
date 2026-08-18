# PocketDisco Phase 0 Android experiment

This is a throwaway native Android experiment. It is not the React Native product scaffold and it has no room backend. It tests the two Android uncertainties called out in the committed build plan.

## Requirements

- Android Studio with JDK 17
- Android SDK 36
- Two Android devices on ordinary Wi-Fi
- An HTTPS MP4 or M4A asset that the team owns or is licensed to stream to each participant
- A controlled HTTPS origin for the YouTube IFrame API test
- The temporary LAN coordinator in `../../tools/phase0_coordinator` for the preferred timing path

No audio asset, provider credential, signed URL, or user token belongs in this repository.

## Build

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on both devices.

## Licensed audio trial

1. Enter different device labels, select the physical output category, and use the same trial ID on both phones.
2. Enter the same non-secret asset label, SHA-256, licensed HTTPS URL, and playback position.
3. Confirm the rights checkbox and preload. Wait until both phones report ready.
4. Start the LAN coordinator with its one-time bearer token. Enter its URL and token on each phone, then take seven time samples on each phone.
5. Create a trial on one phone. Copy the returned trial UUID to the other phone and fetch it there. Both phones verify the asset ID and hash, map the server time to local elapsed realtime, and schedule the same position.
6. Repeat at least ten starts for built-in speakers and wired output separately. Record Bluetooth only as best effort.
7. Export detailed NDJSON and sync-analysis observations from both phones after each trial. Each export is a cumulative snapshot, so keep the latest file from each phone instead of combining successive exports. Mark a coordinator start failure on each phone if a scheduled start never produces playback.

The coordinator never receives or returns a media URL. The app never records the bearer token or full media URL. Plain HTTP coordinator URLs are accepted only for localhost, `.local`, and private LAN addresses. Use this only on a trusted test network.

If the coordinator is unavailable, use the manual common epoch section. One phone generates a target 30 seconds ahead and shares that Unix millisecond value with the other phone. Each phone converts it once and schedules against `SystemClock.elapsedRealtime()`. This fallback depends on device wall-clock agreement and is lower confidence.

The app records the requested target, local monotonic target, play command time, `isPlaying` transition, player position, buffering state, and manual audible-onset marks. A manual mark includes human reaction time. Use an external microphone and a test track with a clear onset for the actual audible-skew result.

The filtered sync-analysis export contains one deduplicated `playback_start` observation per coordinator start. Run it with:

```powershell
python -m tools.sync_analysis phone-a.jsonl phone-b.jsonl --gate-measurement playback_start --format markdown
```

Add `--gate-output wired` when checking the separate wired run.

The default analyzer gate uses `acoustic_onset`. Those timestamps must be annotated from one external capture clock. The per-phone manual audible-onset button is diagnostic only and is not exported as a valid acoustic observation.

## YouTube trial

1. Enter a controlled HTTPS origin and initialize the official IFrame player.
2. Cue a public test video or playlist by official ID.
3. Tap the `Ready to play` button directly below the visible player. This is the required participant gesture.
4. Exercise play, pause, seek, app switching, screen lock, advertisements, unavailable items, and playlist transitions.
5. Export telemetry from each phone.

The WebView keeps standard YouTube controls, branding, metadata, related content, and ads intact. It never hides or overlays the player. Playback is paused when the activity leaves the foreground and never resumes automatically. `onAutoplayBlocked`, lifecycle, screen state, position, error, and playlist-transition events are recorded.

YouTube output is experimental and best effort. Different ads, availability, buffering, and playlist behavior can prevent tight synchronization.
