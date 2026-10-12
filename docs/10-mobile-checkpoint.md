# Android delivery checkpoint

2026-10-12. Desktop remains paused. The owner has merged the Phase 0 harness
and Android multi-output experiment PRs into main. Those experiment merges
do not establish acoustic synchronization or product playback readiness.

## Server connection checkpoint: 2026-10-12

[PR 11](https://github.com/Jay-Parmar/PocketDisco/pull/11) is stacked on PR 10.
No branches were merged. The installed backend remains healthy and unchanged;
restoring the missing SSH/ADB route made the internal apps reachable again.
This is private USB development access, not a public phone endpoint. See
[router forwarding and HTTPS gates](11-deployment-proposal.md#connection-recovery-2026-10-12).

The mobile changes preserve the chosen server before sign-in and across
session expiry, keep credentials isolated when switching servers, and clear
recovered connection errors without hiding unrelated failures. A device test
also exposed a successful-Leave race: the room socket could report removed
membership before the HTTP response arrived. Confirmed leave now clears only
that operation's membership error. HTTP failures and newer errors remain visible.

The first rebuild exposed missing Gradle inputs for the shared TypeScript
client outside the mobile directory. Both bundle tasks now track those sources;
CI checks the input membership. The rebuilt internal and release source maps
match the current client, and both signed internal APKs contain that bundle.

Runtime sources through `d5d2da9` are installed on Nothing, Samsung and the
API 36 emulator. Existing matching development signing keys allowed in-place
updates on all three. No app was uninstalled and no app data was cleared.

Verification:

- Mobile: 150 tests, TypeScript and ESLint pass. Domain: 80 tests and TypeScript
  pass, including the three reproduced leave-notification races and guards
  for newer errors, unrelated errors and failed HTTP requests.
- Android: 36 product native tests, internal/release lint and both APK builds
  pass. Existing compatibility warnings and release blockers remain.
- Room harness: 49 tests and Ruff checks pass. Header searches stay upward,
  taps stay inside visible app bounds, and leave requires Welcome with no
  error banner after bounded settling.
- API: 108 local tests pass with eight real-store tests skipped. All nine
  remote smoke checks pass against the deployed PostgreSQL/Redis backend.
- Restricted HTTPS preparation: 18 tests and four Caddy parser variants pass.
  No proxy or certificate has been installed.

Device evidence is in `.local-tools/mobile-connection-20261012/`:

- `rooms-three-01` stopped at preflight because ADB supplied a blank line in
  its mapping output. The parser fix has a regression test.
- `rooms-three-02` passed nine checks from 14:08:18 to 14:18:00 UTC: health,
  welcome preflight, create, three-member join/presence, readiness, chat,
  background recovery, process recovery and offline cold-start retry. The run
  failed during exit lookup, so it is not recorded as a clean end-to-end pass.
- `rooms-cleanup-03` confirmed the emulator left; the physical-phone cleanup
  was interrupted. Follow-up found Samsung's API mapping missing and restored
  it without replacing any conflicting mapping. `rooms-cleanup-04` retained
  the unsuccessful check rather than overwriting it.
- `rooms-cleanup-05` returned both physical phones to Welcome, but retained
  their observed error-banner flags. Reading the banners exposed the Leave
  race fixed above. These reports are not clean-exit passes.
- `rooms-exit-06` passed all six focused checks on `d5d2da9`, from 14:45:58
  to 14:49:30 UTC. All three updated apps passed welcome/API preflight,
  create/join and presence, then left guests first and host last with no error
  banner. This verifies the exit fix separately; it does not relabel the
  earlier interrupted full run as passing.

Internal APKs and their checksum/signer manifest are under
`.local-tools/mobile-connection-20261012/artifacts/d5d2da9/`:

- `PocketDisco-internal-nothing-emulator.apk` updates those existing installs.
- `PocketDisco-internal-samsung.apk` updates Samsung or installs on a fresh
  internal test device. Do not replace an existing differently signed app.

Both use `http://127.0.0.1:8000` by default and need the private development
route until public HTTPS is activated. The API mappings were retained for
continued use. Disconnecting USB or ending the PC's SSH tunnel breaks that
route. The release build still has no production endpoint or upload signing.

Audio is a separate result: the latest Bluetooth relay regression lost clock
quality; a short USB fanout comparison passed. See the
[capture checkpoint](13-synchronized-capture.md#connection-recovery-regression-2026-10-12).
Neither establishes acoustic sync, Internet-room playback or Play readiness.

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
