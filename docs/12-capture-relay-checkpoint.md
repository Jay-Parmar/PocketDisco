# Capture relay checkpoint

Resumed on 2026-10-12 after the owner's pause. Work is on
`feature/android-capture-relay-lab`, based on `feature/android-demo-playback`
at `6019479`. [PR 9](https://github.com/Jay-Parmar/PocketDisco/pull/9) is stacked
on PR 8. Microcommits are pushed; nothing has been merged. The earlier pause
record is retained below.

This checkpoint describes the pre-synchronization lab. The newer
[receiver timing and internal product milestone](13-synchronized-capture.md)
records protocol-v2 builds, two physical phones and paired-Bluetooth results.

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

## Results after resuming

Tested on the authorized Nothing A142 and API 36 emulator. Device audio links
used ADB loopback TCP, not physical Bluetooth.

- 44 JVM checks pass: 31 protocol, 8 capture-state and 5 generated-tone tests.
- 30 host-only runner checks pass. Both APKs build, Android lint has zero
  errors, and APK signature verification passes. Compatibility and
  presentation warnings remain.
- The complete generated-audio device suite passes: authenticated connection,
  cancelled consent, allowed capture, matching non-silent PCM hashes,
  advancing AudioTrack output, blocked-source silence, receiver background
  disconnect/reconnect and source-session stop.
- The installed music app played our generated MP3. Its selected-app audio
  reached the emulator with matching non-silent PCM hashes and advancing
  output. Capture remained silent while the other source UID played an
  allowed tone. No catalog track or new music-app permission was used.
- A separate wrong-code check rejected both peers with `Join code mismatch`,
  zero connected peers and zero received frames.
- Android's status-chip **Stop sharing** control revoked the lab's projection.
  The final report showed `active: false`, capture stopped with
  `Capture permission revoked`, and zero peers. A separate Android service
  check confirmed there was no remaining projection.
- Dark UI checked on both devices and at 320 dp / 1.5 font scale on the
  emulator. Fixed keyboard restoration covering reconnect controls and
  source content scrolling underneath system bars. Emulator size and font
  settings were restored.
- Fixed worker-startup cleanup and rejection of a competing source before
  forwarding. Both have protocol regression tests.

The first-100-non-silent-frame SHA256 matched on sender and receiver:

| Source | Matching PCM hash |
|---|---|
| Generated tone | `3254192fbea53d1c48b15719d58f62a6f5328ff260c36840dcf3cb5f8f614d20` |
| Generated MP3 in installed music app | `6f52728949c1ae4d81c7cd0e9a071cf83078a03216480b9dcede8f61f686e5ec` |

Both sampled proof points had zero relay and output drops. These are software
observations, not microphone measurements or proof of music-quality playback.
Reports are sampled at different times, so their total frame counts can differ.
Per-check snapshots preserve the matching hashes when reconnect resets counters.

Evidence is kept inside `.local-tools/capture-relay-lab/`:

- `e2e-generated-final-01/results.json`: complete generated-source run.
- `e2e-music-local-02/results.json`: local MP3 and selected-UID isolation.
- `auth-negative-results.json`: wrong-code rejection.
- `revoke-before-02.json` and `revoke-after.json`: supervised OS revocation.
- `phone-lab-*.png`, `phone-source.png` and `emulator-*.png`: UI review with
  a dummy, unused session code.

Earlier failures remain in their original output directories, including the
consent-transition and keyboard failures. The first OS-stop attempt stopped
at the runner's guard because Android used a dialog title instead of an
activity component in its focused-window entry. Window-owner lookup and three
regression checks now cover that case; the supervised retry passed.

### APKs for the next phone

Tested local builds are copied to
`.local-tools/capture-relay-lab/artifacts/6506bc5/`:

| APK | SHA256 |
|---|---|
| `PocketDisco-Capture-Lab-debug.apk` | `2cdbaa4ac89bcdc4bf3dd8ae29d15f4bcc8cfa9cd5f6983c7ea7a715508654f0` |
| `PocketDisco-Test-Source-debug.apk` | `c4ad5215880889f54734d633099aba7a4d7f9a9c610babbef2efc565c83cc906` |

Install Capture Lab on each receiver and Test Source on the sending phone.
These debug packages are separate from the product app. Use the same local
build family when updating; CI can use a different debug signing key.

## Next hardware tests

1. Install the Capture Lab APK on another physical phone. Follow the
   [paired-Bluetooth runbook](../experiments/android-capture-relay/README.md#three-phone-paired-bluetooth-check)
   with generated audio and low volume. Start with one direct link.
2. Add a third physical phone for A-to-B-to-C relaying. The emulator cannot
   substitute for physical Bluetooth radio evidence.
3. Measure drops, range, disconnect recovery, battery impact and actual
   speaker-to-speaker delay. Acoustic alignment and clock-drift correction
   are not implemented by this capture lab.
4. Keep the Internet-room product roadmap and Phase 0 acoustic milestone
   separate. This diagnostic is not a Play release, Spotify approval, or
   permission to redistribute music-app catalog audio.

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

Artifacts at the pause, superseded by the builds above:

| APK | SHA256 |
|---|---|
| `experiments/android-capture-relay/lab/build/outputs/apk/debug/lab-debug.apk` | `46c7315427a20c861a0dd2548265b76b69316eb7077487bc003aeb24f225c6f7` |
| `experiments/android-capture-relay/source/build/outputs/apk/debug/source-debug.apk` | `ccc3c5fd9894536cc0df3d765427bfa65b2b3be791435cb36239dfaf4309e577` |

These are local debug diagnostics, not final release artifacts. The generated
MP3's provenance is in [the source notes](../experiments/android-capture-relay/source/README.md).
The failed automated run is retained at
`.local-tools/capture-relay-lab/e2e-generated/results.json`. APK review evidence
is in `.local-tools/apk-review/yt-music-8.40.54-review.json`.

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
