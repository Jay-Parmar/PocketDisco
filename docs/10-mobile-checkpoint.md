# Android delivery checkpoint

2026-10-07. Desktop remains paused. No existing experiment PR was merged.

## Review branches

- [PR 5: Android private rooms and chat](https://github.com/Jay-Parmar/PocketDisco/pull/5)
  targets main directly. React Native UI, secure local guest sessions, room
  recovery, FastAPI, PostgreSQL migrations, Redis live state, and CI.
- [PR 6: CC0 test audio](https://github.com/Jay-Parmar/PocketDisco/pull/6)
  targets main independently. Three complete AAC/MP4 tracks, credits, license
  declarations, reproducible conversion, and file verification.

The owner reviews and merges. These changes do not close the physical audio
measurement gates from Phase 0 or make the application ready for Play release.

## Verified

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

1. Kotlin player/provider adapter and the shared playback timeline: prepare,
   play/pause, seek, late join, reconnect, and clock/drift measurement.
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
