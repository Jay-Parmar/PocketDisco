# Capture relay checkpoint

Paused at the owner's request on 2026-10-12. Resume from
`feature/android-capture-relay-lab`, based on `feature/android-demo-playback`
at `6019479`. This branch has local microcommits but has not been pushed and
does not yet have a PR. The parent is PR 8. Do not merge without owner review.

## Scope

The owner authorized a separate capture-and-relay diagnostic using the USB
phone and emulator, then requested a polished UI. The implementation is in
[the native lab](../experiments/android-capture-relay/README.md), separate
from the React Native product app. The Internet-room architecture, provider
approval gates and Phase 0 acoustic milestone remain unchanged.

The lab includes selected-app playback capture with Android consent,
authenticated PCM links, bounded fan-out, deduplication, a three-hop limit,
paired Bluetooth sockets and a loopback USB/TCP test transport. It uses
16 kHz mono PCM for diagnostics, not music-quality synchronized playback.
Receivers must remain foreground. It is not an ordinary Bluetooth speaker,
automatic discovery network, completed mesh product or Play release.

The source app generates permitted and blocked test tones. Its local-file
button opens only our bundled generated MP3 in the already-installed music
app. No external catalog, Spotify integration, source-app modification or
backend audio proxy was added. Captured PCM is not stored.

The supplied APK's static review found an allow-capture AudioAttributes path.
That is not proof of every playback path, patch provenance, runtime capture,
or permission to redistribute a provider's catalog. The supplied APK was not
installed, replaced, modified or uploaded during this work.

## Results before the pause

- All 40 JVM checks pass: 27 relay, 8 capture-state and 5 generated-tone tests.
- Both debug APKs build. Android lint has zero errors; compatibility and
  presentation warnings remain.
- The device runner's 12 host-only tests pass.
- Both apps install and cold-launch on the Nothing A142 and API 36 emulator.
- Manual phone-to-emulator USB/TCP test passed: selected-source audio reached
  the receiver, matching the first 100 non-silent PCM frames by SHA256.
  Receiver AudioTrack progress advanced with zero dropped output frames at
  the sampled check. This is software evidence, not a microphone recording.
- Manual blocked-source test passed: capture advanced from 7059 to 7260
  frames while the non-silent count stayed at 2239. The original test tone
  continued locally with capture disabled by its player policy.
- The polished dark UI and generated MP3 option build and install. Full
  visual review and the MP3 playback/capture test are still pending.
- The automated device run established authenticated peers, then failed on
  a foreground-window check during the projection dialog transition. The
  dialog remained visible and was subsequently cancelled. Do not report the
  complete device suite as passed.

Latest local artifacts:

| APK | SHA256 |
|---|---|
| `experiments/android-capture-relay/lab/build/outputs/apk/debug/lab-debug.apk` | `46c7315427a20c861a0dd2548265b76b69316eb7077487bc003aeb24f225c6f7` |
| `experiments/android-capture-relay/source/build/outputs/apk/debug/source-debug.apk` | `ccc3c5fd9894536cc0df3d765427bfa65b2b3be791435cb36239dfaf4309e577` |

These are local debug diagnostics, not final release artifacts. The generated
MP3's provenance is in [the source notes](../experiments/android-capture-relay/source/README.md).
The failed automated run is retained at
`.local-tools/capture-relay-lab/e2e-generated/results.json`. APK review evidence
is in `.local-tools/apk-review/yt-music-8.40.54-review.json`.

## Resume here

1. Confirm the shared USB phone and emulator are available again. Do not
   interact with another chat's app or overwrite its ADB mappings.
2. Fix the runner's projection-transition handling. A transient empty focused
   window should get a short retry; an unrelated foreground app must still
   stop the test. Validate that a consent dialog names Capture Lab before
   interacting with it. Add a regression test.
3. Rerun [the device suite](../tools/capture_relay_e2e/README.md). Verify consent
   cancellation, allowed/blocked capture, disconnect/reconnect and clean stop.
   Preserve failed reports rather than replacing them with passing evidence.
4. Run the optional generated-MP3 local-file test in the installed music app.
   Its media permission was already granted by the owner. Do not grant new
   music-app permissions, replace it or open catalog content for this test.
5. Inspect the new UI on both devices and save app-only screenshots without
   a reusable session code. Check large text and narrow layouts.
6. Test OS capture revocation, invalid session code and selected-UID isolation
   on hardware. Paired Bluetooth with a second physical phone, third-phone
   relaying, range, drift and audible synchronization are still unverified.
7. Review, push this feature branch and create a stacked PR against
   `feature/android-demo-playback`. Run the new lab CI and provide the owner
   with APKs and an honest third-phone test checklist.

At pause, only the two diagnostic packages were force-stopped on both
devices. The lab's port-forward mapping had already been removed, and no
ADB forwards remained. No backend, router, firewall or music-app state was
changed. The emulator remains booted; no test or development agent is running.

## Platform references

- [Android playback capture](https://developer.android.com/media/platform/av-capture)
- [Projection consent and lifecycle](https://developer.android.com/media/grow/media-projection)
- [Paired Bluetooth sockets](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices)
- [Emulator networking and simulated Bluetooth](https://developer.android.com/studio/run/emulator-networking)

Capture does not delay source playback. Emulator transport results do not
establish physical Bluetooth performance or audible synchronization.
