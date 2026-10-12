# Synchronized capture milestone

Implemented on 2026-10-12 on `feature/synchronized-capture-playback`, stacked on
`feature/android-capture-relay-lab`. [PR 10](https://github.com/Jay-Parmar/PocketDisco/pull/10)
is based on PR 9; nothing was merged. The owner requested synchronized playback,
private rooms using the installed music app, and Play readiness. Two physical
phones and the emulator were available for the checks below.

Current handoff: the Samsung has locked. Its owner must unlock it before the
fixed-build sustained test and product-entry retry can run. PR 10 stays draft.

## Scope

This implementation schedules captured PCM against one source clock.
Receivers share a fixed future deadline rather than playing frames on arrival.
Clock probes travel to the original source through the same authenticated links.
Relay hops do not add another playback delay. Queues, clock estimates and
reconnect state remain bounded. Late or stale audio must not play as a backlog.

The Android product has an internal-only entry to the shared nearby
engine. Nearby sessions use a separate 128-bit invitation code and paired
Bluetooth. They are not automatically authorized by Internet-room membership,
and cannot reach remote friends over the Internet. Existing private-room chat
and the independent-file provider remain separate. No backend audio proxy is
being added. Capture stays disabled in the public release build until its
product, privacy and distribution gates are settled.

Protocol version 2 rejects older peers. The source timeline has a 1.5-second
lead; clock probes, reverse routes and queues have fixed limits. A fresh
source-clock mapping is required before receiver scheduling. Missing, stale
or late data causes bounded silence, rebuffering or stopped output, not a
backlog replay. AudioTrack timestamps calibrate output lead with at most three
startup retries. Audio remains 16 kHz mono PCM16, not music-quality encoding.

The dark native screen shows receiver-clock uncertainty, scheduled starts,
output error and recovery counters. The private-room card labels the separate
nearby session and reconnects room chat on return. Back stops the native
session. The shared code admits nearby members; it is not a host-role or
Internet-room ACL. The first admitted source is pinned for that session.

Use generated music opened in the installed music app for repeatable checks.
Do not replace or patch that app, bypass capture restrictions, extract protected
streams, record microphones, or redistribute catalog tracks without permission.
The installed APK is not evidence of those rights. Spotify remains gated.

[Android playback capture](https://developer.android.com/media/platform/av-capture)
does not delay the source app. Only receiving outputs can share the new timeline.
The sending phone's original speaker is not a synchronized receiver. A quiet
sender and two independent receivers are needed to assess receiver-to-receiver
sound. AudioTrack timestamps are software evidence, not acoustic measurements.

## Test loop

1. Pure tests for clock offsets, jitter, stale probes, routing, deadlines,
   bounded queues, missing frames and cancellation.
2. Android build and lint; preserve blocked-source, selected-UID, wrong-code,
   consent-revocation and background-stop checks.
3. USB/TCP sender plus two receivers, including the added physical phone.
   Compare received content and scheduled output observations.
4. Paired physical Bluetooth, then mixed Bluetooth and USB relay. Keep
   software evidence separate from measured speaker alignment and radio range.
5. Build and test the internal product entry. Record exact artifacts, results
   and remaining gates before requesting review.

## Verified results

- 94 capture JVM tests pass: 60 protocol, 29 shared-core and 5 source tests.
  A CI failure exposed an observation race in the stale-clock test. A
  controlled callback barrier now reproduces that ordering; ten complete
  protocol reruns passed without relaxing assertions or changing production
  behavior.
- 56 capture-runner and 13 mobile-runner host tests pass.
- 135 mobile JavaScript tests, TypeScript and ESLint pass. All 36 product-native
  tests pass. Android lint has no errors for the lab, source, shared core,
  internal product or release variant. Compatibility warnings remain.
- Lab, Test Source, internal product and unsigned shrunk release APKs build.
  The three installable test APKs pass signature verification.
- The actual release APK manifest has no Bluetooth/capture permissions or
  capture components. No `capturelab` or `capturecore` package appears in its
  defined DEX packages. The internal capture Activity is non-exported. An
  Android 7 through 9 runtime check is still needed for the API-gated entry.

The hardware was a Nothing A142, Samsung S25 Ultra and API 36 emulator.
Both phones had version 8.40.54 of the installed music app. Tests opened our
generated MP3 only. The owner approved Samsung's local-audio permission after
the first run stopped at its prompt. No music app was replaced or patched.

All four checks passed in each of these three-device runs:

| Evidence directory | Source and transport | Sustained observations |
|---|---|---|
| `sync-mixed-music-02` | Nothing music app -> Bluetooth -> Samsung -> USB/TCP -> emulator | 8 fresh samples per receiver, 09:57:51 to 09:57:59 UTC |
| `sync-two-physical-02` | Emulator generated source -> USB/TCP -> both physical phones | 8 fresh samples per receiver, 10:01:05 to 10:01:13 UTC |
| `sync-samsung-music-02` | Samsung music app -> Bluetooth -> Nothing -> USB/TCP -> emulator | 8 fresh samples per receiver, 10:08:47 to 10:08:54 UTC |

Each check established two authenticated receivers, scheduled output, matching
first-100-non-silent-frame PCM hashes, and advancing output with fresh reports.
There were no additional resyncs, output/relay drops, late frames or inserted
silence during the sampled windows. Startup calibration/rebuffering had
already caused one or two resyncs on some receivers, discarding 73 to 162
output frames.

| Run / receiver | Reported output error, ms | Clock uncertainty, ms |
|---|---|---|
| Nothing source / Samsung | -3.042 to -2.142 | 7.256 to 13.896 |
| Nothing source / emulator | +4.282 to +4.346 | 8.913 to 25.553 |
| USB / receiver 1 | +3.592 to +4.179 | 2.331 to 3.045 |
| USB / receiver 2 | +11.263 to +11.650 | 1.414 to 1.520 |
| Samsung source / Nothing | +15.787 to +16.170 | 8.037 to 14.187 |
| Samsung source / emulator | +3.181 to +3.218 | 12.977 to 31.188 |

These are short software observations against each receiver's estimated
deadline, not acoustic skew, a p95 result or eight independent start trials.
Prefix hashes do not prove an entire session. Clock uncertainty is separate
from the reported output error. The source's original speaker remains outside
this timeline. Three physical phones linked across two Bluetooth hops have
not been tested.

Other completed device checks:

- `sync-generated-regression-01`: all six original safety checks pass,
  including consent cancellation, blocked-source silence, background
  disconnect/reconnect and capture stop.
- `product-nearby-04`: all four product-entry checks pass on Samsung and the
  emulator against an isolated local-test API. The visible UI creates a room,
  opens nearby audio, authenticates a receiver, captures the generated tone
  after Android consent and verifies matching PCM plus advancing scheduled
  output. Back returns to the room, removes the capture service and leaves
  zero receiver peers with no usable clock. The playing observation is saved
  separately from the stopped observation.

All results are under `.local-tools/capture-relay-lab/<directory>/results.json`.
They contain counters and hashes, not captured PCM or session codes. Earlier
failures are retained, including foreground guards, the Samsung permission
prompt, a UI-placeholder mismatch and the product runner's welcome-screen
precondition. They are not counted as passing runs.

## Test artifacts

The short device runs above used builds preserved in
`.local-tools/capture-relay-lab/artifacts/5a6f5dd/`.

A subsequent continuous-tone run, `sync-usb-soak-01`, failed after its four
short checks and three additional five-second samples. Samsung lost its link;
the source reported `Peer write timeout` with no queue drops. Samsung stopped
scheduled output when its clock was lost. The failed run is retained.

Code review found a watchdog race: the timeout predicate read its deadline
twice, and a completed write could clear it between those reads. A deterministic
test reproduced that false expiry. Commit `ff12795` reads one snapshot and
protects timeout decisions and deadline updates with the same short state lock.
Transport I/O stays outside the lock. No timeout or queue limit was widened.
All 60 protocol tests passed in six complete runs after the fix. This supports
the diagnosis, but does not replace a new hardware soak.

The fixed runtime candidates are in
`.local-tools/capture-relay-lab/artifacts/ff12795/`. Build, unit tests, lint and
APK signatures pass; the post-fix sustained hardware run is pending unlock.

| APK | SHA256 |
|---|---|
| `PocketDisco-internal.apk` | `726c6de3263238e565c16d950451505ad7247487a8df6473299ebfeeeb2e6180` |
| `PocketDisco-Capture-Lab-debug.apk` | `d45b0b4737c138f9a99a585f8e1390bec72c7aa170632e28979f3314de64999d` |
| `PocketDisco-Test-Source-debug.apk` | `c4ad5215880889f54734d633099aba7a4d7f9a9c610babbef2efc565c83cc906` |

Capture Lab is updated on all three devices. The product is updated on Samsung.
Android rejected product updates on Nothing and the emulator because their
older internal packages have a different signing key. Those apps and their
saved guest sessions remain untouched. Replacement needs the owner's approval;
it is not required for the separate Capture Lab tests.

For a third-phone nearby test, install Capture Lab on it and use the same
new session code on all peers. Follow the [paired-Bluetooth runbook](../experiments/android-capture-relay/README.md#three-phone-paired-bluetooth-check).
Install Test Source on the sender when using generated fixtures. No backend
is needed for that lab test. The internal product instead needs the existing
room API; its default loopback endpoint is for USB testing, not public access.

These are debug/internal packages, not Play artifacts. CI can use a different
debug signing key; do not uninstall an existing package to bypass a mismatch.
Update every lab peer to protocol v2. The unsigned release APK is a build check
only and is not the third-phone test artifact.

## Remaining work

1. Unlock Samsung, then rerun USB fan-out with the tracked runner's
   `--samples 180` option in a fresh output directory. It now requires new
   non-silent audio and unchanged peer/recovery/drop counters for every sample,
   retaining partial samples and final device reports on failure. Repeat the
   Bluetooth local-file and product-entry checks on the fixed APKs.
2. Measure actual receiver-speaker skew over ten starts and longer playback,
   including route changes, dropout recovery and a third physical Bluetooth
   node. The nominal capture-sample timeline is not hardware-clock drift
   tracking; longer sessions need measurement.
3. Resolve the product source and rights before public capture distribution.
   A local-file test inside the installed music app does not establish rights
   or capture support for its provider catalog. Do not bypass blocked sources.
4. Implement Internet-room prepare/commit and the independent-file timeline
   separately. The internal nearby card does not complete shared Internet
   playback or couple room membership to nearby admission.
5. Complete the release gates below. Keep desktop and unrestricted mesh work
   paused; neither was added in this milestone.

## Play gates remain open

This milestone alone is not a release approval. Public HTTPS/WSS, upload signing,
privacy and account deletion, chat safety, media distribution rights, native
16 KB compatibility, Console declarations and required tester access remain
separate work. See the [mobile checkpoint](10-mobile-checkpoint.md) and
[deployment notes](11-deployment-proposal.md). Do not describe a debug APK or
software clock result as a Play-ready or acoustically synchronized release.
