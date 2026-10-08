# Android delivery checkpoint

2026-10-08. Desktop remains paused. The owner has merged the Phase 0 harness
and Android multi-output experiment PRs into main. Those experiment merges
do not establish acoustic synchronization or product playback readiness.

## Review branches

- [PR 5: Android private rooms and chat](https://github.com/Jay-Parmar/PocketDisco/pull/5)
  targets main directly. React Native UI, secure local guest sessions, room
  recovery, FastAPI, PostgreSQL migrations, Redis live state, and CI.
- [PR 6: CC0 test audio](https://github.com/Jay-Parmar/PocketDisco/pull/6)
  targets main independently. Three complete AAC/MP4 tracks, credits, license
  declarations, reproducible conversion, and file verification.
- `feature/android-demo-playback` is stacked on
  [PR 7: self-hosted backend](https://github.com/Jay-Parmar/PocketDisco/pull/7).
  Native Media3 playback, generated demo audio and local preview controls.
  Merge its parent first, then retarget the playback PR to main.

The owner reviews and merges. These changes do not close the physical audio
measurement gates from Phase 0 or make the application ready for Play release.

## Local playback checkpoint: 2026-10-08

The room now contains a clearly labeled local audio preview. One generated
AAC/MP4 clip plays only after an explicit tap. Native scheduling, preparation,
pause, seek, replay, observed progress and lifecycle cleanup are implemented.
Readiness does not start audio. Shared room timelines are not connected yet.

Verified locally at code revision `74b5f88`:

- Mobile: 120 tests, strict TypeScript and ESLint pass.
- Domain: 74 tests and strict TypeScript pass.
- Native: 30 tests pass, including stale lifecycle callbacks, interrupted
  scheduling and repeated focus-loss events.
- Internal and minified unsigned release APKs build. Both lint variants report
  zero errors and 16 warnings. Existing compatibility warnings remain.
- Audio: 11 tests pass. FFprobe, full decode and midpoint seek decode pass.
  Both APKs contain the reviewed demo bytes and bundled JavaScript.

After the owner confirmed a shared-device slot, the new internal APK was installed
on the Nothing A142 and API 36 emulator. All eight room/chat UI checks pass
against the deployed backend through an SSH tunnel, including process recovery
and offline saved-room retry. This is not a public HTTPS or separate-network test.

Both devices also pass seven local playback checks: no autoplay on entry,
advancing native playhead before pause, paused five-second seeks, natural end,
replay, background return without resuming, and process restart without autoplay.
Timed screenshots confirm the playing UI and advancing position; no acoustic
recording was made. An initial UI-idle polling attempt missed the short playing
state, so the repeat used timed captures and paused-state observations.

Reports and screenshots remain under `.local-tools/mobile-e2e-playback-20261008/`
and `.local-tools/mobile-playback-20261008-retry/`; the initial playback report is
retained separately. After testing, only PocketDisco was stopped, both devices
were returned to Home, and their original ADB reverse mappings were restored.
No other app or app data was changed. The backend deployment was not changed.

Lock-screen, real focus/headset interruption, leaving during active audio,
cross-device start timing and measured audible output remain unverified.
JVM fakes and component tests do not close these device/acoustic gates.

The [mobile runbook](../apps/mobile/README.md) describes the preview boundaries;
[generated audio provenance](../tools/demo_audio/README.md) records the asset.

## Earlier private-room verification: 2026-10-07

- Mobile: 88 Jest tests, strict TypeScript, and ESLint pass.
- Shared domain: 54 tests and strict TypeScript pass.
- API: 59 local tests pass; eight real-store tests are intentionally skipped
  without PostgreSQL and Redis. [CI](https://github.com/Jay-Parmar/PocketDisco/actions/runs/37649853876)
  passes all 67 tests on Python 3.10 and 3.12, plus migration rollback/reapply.
- Native: four encryption tests, internal and minified unsigned release builds,
  and both lint variants pass locally. Release APK ZIP alignment passes the
  16 KiB check. This is not a full 16 KiB device compatibility result.
- Devices: Nothing A142 on Android 16 and an API 36 emulator both run the
  internal app. Create/join, live presence, readiness, two-way chat, foreground
  recovery, encrypted session/chat restoration after process death, and saved
  room retry after an offline cold start pass.
- Audio: 20 tool tests and all three hash/format/full-decode/midpoint-seek
  checks pass locally and in the audio PR's Linux CI. No app playback tested.

The initial device launch found a native debug/release ABI mismatch in the
internal variant. Its dependency fallback now matches release compilation;
both devices pass cold launch with the fix. The device test harness also skips
clipped accessibility bounds so it does not tap controls hidden by the composer.

Raw device reports and screenshots stay under `.local-tools/mobile-e2e*/`.
Screenshots include test invites and are not committed. The test can clear only
the internal app's scratch session when explicitly given `--reset-test-session`.

The [2026-10-08 backend checkpoint](11-deployment-proposal.md) supersedes the
deployment status below. The server is deployed privately and remote API tests
pass. The new device UI run was interrupted by another app using the devices;
it is not a complete remote phone/emulator result. Public HTTPS remains pending.

## Resume locally

Use [mobile setup](../apps/mobile/README.md), [API setup](../services/api/README.md),
and [the UI smoke loop](../tools/mobile_e2e/README.md). The scratch API database
for this run is `.local-tools/mobile-alpha-v2.db`. Start one local-test worker
and reverse port 8000 on both devices. Nothing is deployed to a public endpoint.

The media branch's working tree is `.local-tools/worktrees/media-pr/`. Its
tracked assets will appear at `assets/test-audio/` after that branch is merged.
The control API never serves or redistributes music.

## Remaining before release

1. Connect the Kotlin player to the shared playback timeline: server/native
   clock estimation, room prepare/commit, play/pause/seek, late join, reconnect,
   drift measurement and correction. The local preview does not perform these.
2. Audible synchronization tests on two physical phones. An emulator cannot
   establish speaker or Bluetooth output skew. Multi-output Bluetooth remains
   hardware/platform gated and is not implemented in this product app.
3. Production HTTPS/WSS deployment, secrets, backups, load/recovery testing,
   account deletion/export, retention, and moderation.
4. Final package ownership, signing, privacy policy, Data Safety, support
   contact, license provenance review, and Play testing requirements.
5. Dependency advisory review. The mobile README records the current npm audit
   results; green functional tests do not resolve those advisories.

The next product increment is controlled-audio playback, not YouTube extraction,
Spotify synchronization, public discovery, voice chat, or desktop work.

## Additional Android compatibility finding

Both tested devices report a 4096-byte memory page size. Each local APK contains
11 native libraries per 64-bit ABI. Their LOAD segments pass 16 KiB alignment,
but nine libraries per ABI fail the RELRO-end alignment condition in the current
[Android guidance](https://developer.android.com/guide/practices/page-sizes#check-relro-security-flag).
Representative headers were cross-checked with NDK `llvm-readelf`.

This is a static release blocker, not an observed crash on a 16 KiB device.
It includes prebuilt React Native/Hermes dependencies, so changing only the app's
linker flags is not sufficient. Resolve compatible native dependencies and test
on a genuine 16 KiB environment before claiming support or submitting to Play.
Do not disable RELRO or patch binary headers to bypass the check. Detailed
scratch evidence is in `.local-tools/elf-alignment/`.
