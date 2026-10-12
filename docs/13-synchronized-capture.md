# Synchronized capture milestone

Implemented on 2026-10-12 on `feature/synchronized-capture-playback`, stacked on
`feature/android-capture-relay-lab`. [PR 10](https://github.com/Jay-Parmar/PocketDisco/pull/10)
is based on PR 9; nothing was merged. The owner requested synchronized playback,
private rooms using the installed music app, and Play readiness. Two physical
phones and the emulator were available for the checks below.

Current handoff: build `89be729` passed the earlier sustained relay, USB,
generated-file and capture safety checks below. A later same-day Bluetooth
regression lost its clock estimate; the USB comparison passed. Reliable
Bluetooth timing is still open. PR 10 stays draft pending this regression,
acoustic validation and the separate product/release gates.

## Connection-recovery regression: 2026-10-12

Reports are under `.local-tools/mobile-connection-20261012/`. Capture Lab stays
at `89be729`; no timing limits or source-app settings were changed. The source
was the owned generated MP3 opened in Nothing's installed music app, not its
provider catalog.

`relay-regression-01` stopped before capture because Samsung's lab package was
absent. The existing lab and generated-source APKs were installed in its normal
user profile without removing or resetting any app.

`relay-regression-02` ran from 13:59:57 to 14:02:08 UTC: Nothing to Samsung over
paired Bluetooth, then Samsung to the emulator over USB. Authentication and
initial scheduled output passed. Matching audible-prefix PCM hashes reached
both receivers, but the stable audio/timing check failed. The final clock
round trips were 889.761 ms on Samsung and 923.766 ms on the emulator. Both
receivers had no usable clock samples and stopped in `waiting_clock`. Each
had 50 rejected replies; the source and relay links stayed connected with
empty application relay queues. The 200 ms clock admission limit was not
relaxed. Matching bytes alone do not establish synchronized output.

`relay-usb-03` used the same sender and generated file with separate USB paths
to Samsung and the emulator. All four checks passed from 14:32:48 to 14:34:10
UTC. Both receivers kept eight clock samples with no rejected replies or probe
timeouts. Eight fresh non-silent observations per receiver ran from 14:33:59
to 14:34:08 UTC without new recovery, drops, late frames or inserted silence.

| Receiver | Output error, ms | Clock uncertainty, ms | Startup starts / resyncs / dropped frames |
|---|---|---|---|
| Samsung | +6.528 to +7.016 | 3.330 to 4.475 | 3 / 2 / 158 |
| Emulator | +3.763 to +3.835 | 2.609 to 3.995 | 1 / 0 / 0 |

Samsung also had one late frame before the sample baseline. Both receivers'
audible-prefix hash matched the source and the failed Bluetooth run. These
reports sample devices sequentially; final frame totals need not be identical.

The comparison points toward the Bluetooth/relay path, but does not isolate
radio latency from reader stalls or scheduling. No speculative timing fix was
made. Instrument that path and repeat sustained checks before claiming a
resolution. This short USB result is not an acoustic measurement or a public
music-catalog test, and it does not delay the original music app's speaker.

After cleanup, all three devices reported no active media projection. The
runner removed its relay mappings and restored each prior media volume. The
separate API mappings remain for private-room testing.

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

- 111 capture JVM tests pass: 77 protocol, 29 shared-core and 5 source tests.
  A CI failure exposed an observation race in the stale-clock test. A
  controlled callback barrier now reproduces that ordering; ten complete
  protocol reruns passed without relaxing assertions or changing production
  behavior.
- 78 capture-runner and 13 mobile-runner host tests pass.
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
the diagnosis. The post-fix USB run below did not reproduce the timeout.

### Post-fix device checks

`sync-usb-soak-02` passed all four checks on the fixed runtime. Its 180 fresh
samples per receiver span 11:37:09 to 11:40:43 UTC, a 214-second window.
The emulator sent a continuous generated tone to both physical phones over
USB/TCP. Both receivers stayed clock-ready and playing, with no additional
starts, resyncs, drops, late frames or inserted silence during sampling.

| Receiver | Output error, ms | Clock uncertainty, ms | Startup starts / resyncs / dropped output frames |
|---|---|---|---|
| Nothing | -14.876 to -14.254 | 1.877 to 41.796 | 3 / 2 / 145 |
| Samsung | -3.994 to -1.424 | 1.236 to 35.085 | 2 / 1 / 74 |

Startup recovery happened before the sampling baseline. This is not an
acoustic measurement or a claim of recovery-free startup.

`sync-samsung-music-03` also passed all four checks. Samsung's installed music
app played the generated MP3 through Bluetooth to Nothing, then USB/TCP to
the emulator. Eight fresh samples per receiver span 11:42:11 to 11:42:19 UTC.
Nothing reported output error +9.413 to +9.816 ms and clock uncertainty
10.910 to 16.892 ms. The emulator reported +3.174 to +3.251 ms and uncertainty
11.620 to 26.762 ms. Recovery occurred before sampling; no extra recovery or
drops occurred in that short window.

The reverse `sync-mixed-music-08` run passed connection, initial scheduling and
matching audio checks, but failed strict sampling when Samsung restarted
output. The emulator had already lost clock readiness during the audio-hash
check. Links stayed connected and transport drop counters stayed at zero.
Both receivers recovered, which does not turn the run into a pass. Clock
readiness requires three accepted samples within five seconds, and reports
did not yet include probe acceptance or rejection counts. More diagnostic
evidence is needed to distinguish delayed replies from rejected samples.

Earlier reverse runs `sync-mixed-music-03` through `-07` stopped on test setup
or scrolling. A clipped Host button under Samsung's status bar caused the
initial missed action. Subsequent conservative scroll margins rejected valid
controls. These failures are retained, not counted as playback passes.
`product-nearby-05` reached scheduled receiver output but stopped at the
diagnostic button; its final hash and cleanup assertions did not complete.

`product-nearby-06` passed all four checks on Samsung and the emulator after
the test driver was corrected. The 12:15:05 to 12:16:39 UTC run verified the
room-to-nearby handoff, matching generated audio, advancing scheduled output
and capture cleanup on Back. Its playing report showed +4.285 ms output error,
1.395 ms clock uncertainty and no resyncs or output drops. The final receiver
report had zero peers and no usable clock.

The test driver now reads Android's actual system-bar geometry before tapping,
uses short corrective swipes, and checks a fresh TCP listener report before
connecting. Failure evidence includes the post-audio baseline. Timing and
stability assertions are unchanged.

### Clock diagnostics

Commit `b791d2a` adds retained sample count, completed local probe writes,
accepted and rejected estimator replies, local probe timeouts, and last reply
elapsed/network round-trip durations. Forwarded probes do not inflate local
counts. Totals and last durations survive mapping expiry and parent disconnect;
a new relay node starts fresh. `clockAgeMillis` is the age of the best retained
sample, not the time since the last reply.

The new tests cover partial three-to-two sample expiry, rejected RTTs,
timeouts, identity/replay checks and forwarded-probe exclusion. All 66 protocol
tests passed in three full reruns. This diagnostic change does not alter
probe cadence, timing limits, authentication, routing or playback behavior.

The fixed runtime candidates are in
`.local-tools/capture-relay-lab/artifacts/ff12795/`. Build, unit tests, lint and
APK signatures pass. The post-fix USB soak and Samsung-source short Bluetooth
run above used these exact APKs. The reverse Bluetooth recovery remains open.

| APK | SHA256 |
|---|---|
| `PocketDisco-internal.apk` | `726c6de3263238e565c16d950451505ad7247487a8df6473299ebfeeeb2e6180` |
| `PocketDisco-Capture-Lab-debug.apk` | `d45b0b4737c138f9a99a585f8e1390bec72c7aa170632e28979f3314de64999d` |
| `PocketDisco-Test-Source-debug.apk` | `c4ad5215880889f54734d633099aba7a4d7f9a9c610babbef2efc565c83cc906` |

The clock-diagnostic build is preserved separately in
`.local-tools/capture-relay-lab/artifacts/b791d2a/`. All Android tests, build and
lint tasks pass. Capture Lab is updated on all three devices and the product
is updated on Samsung. Its sustained Bluetooth test failed as recorded below.

| APK | SHA256 |
|---|---|
| `PocketDisco-internal.apk` | `6aa1be02acc20b6ce5b055d50f950bef9182be31bc06f09e4cf2f0fb0fb647ec` |
| `PocketDisco-Capture-Lab-debug.apk` | `6a2e4ca2b3ab56931f70625f943e138d744151f13f4d8e451fdce48085c478dd` |
| `PocketDisco-Test-Source-debug.apk` | `c4ad5215880889f54734d633099aba7a4d7f9a9c610babbef2efc565c83cc906` |

`sync-bluetooth-soak-01` requested 180 continuous-tone samples on this build:
Nothing -> Bluetooth -> Samsung -> USB/TCP -> emulator. Connection, initial
scheduling and matching audio passed. Sampling failed after 23 complete
observations when the emulator resynchronized. Samsung remained stable.

The emulator rejected four replies with network RTTs of 217.45, 213.88, 250.81
and 243.89 ms, above the unchanged 200 ms limit. Its last completed sample had
three retained clock samples. The final report had already recovered to four,
but unscheduled frames increased from 20 to 30, resyncs from zero to one and
discarded output frames from zero to 58. Peers stayed connected, with no relay
drops or probe timeouts. Output error stayed between +2.869 and +2.960 ms before
recovery. The exact unready instant was between reports; sample expiry after
rejected replies is the supported diagnosis, not an observed disconnect or
output-calibration failure. The failed run and partial samples are retained.

### Clock maintenance fixes

Commit `e0d05a2` fixes the missing age contribution to uncertainty. A line-leading
plus was parsed separately from the Kotlin initializer. Two new tests failed
before the fix, then all 68 protocol tests passed. They check the intended
200 ppm age allowance and loss of readiness above the unchanged 100 ms limit.

Commit `89be729` replaces permanent one-second probing with 500 ms maintenance.
Startup or a drop below six usable samples allows up to seven probes at 100 ms
spacing, then maintenance resumes. Restoring the reserve ends the rapid cadence
immediately. One pending request and the existing one-second timeout prevent
retry buildup. Cadence uses probe-admission time, not socket-write completion;
delayed watchdog ticks do not send catch-up bursts. Parent replacement resets
the schedule. The separate control-rate limit remains 128 packets per second.
Sample age, RTT, uncertainty, hop, authentication and queue limits are unchanged.

Six of eight schedule tests failed against the old cadence, including the
four-slow-reply regression. The final schedule and authenticated-link refill
test passed in three complete runs of all 77 protocol tests. The test evidence
is in `.local-tools/capture-relay-lab/clock-age-red/` and `clock-probe-red/`.
One cold-cache mobile UI test hit its five-second timeout. A complete rerun
passed all 135 tests in 1.5 seconds; TypeScript and ESLint also passed. No test
timeout or assertion was relaxed.

The candidate is preserved in `.local-tools/capture-relay-lab/artifacts/89be729/`.
Android tests, lint, builds and APK signatures pass. Capture Lab is installed
on all three devices, with the internal product updated on Samsung.

| APK | SHA256 |
|---|---|
| `PocketDisco-internal.apk` | `01c6f865bd7cb0b590f1aa621388f887bf344e6497f02d8997bade221a934322` |
| `PocketDisco-Capture-Lab-debug.apk` | `235752ff102083331670a9b9bc1ed234da9df8f32093fc13d9d8bb0313ee20df` |
| `PocketDisco-Test-Source-debug.apk` | `c4ad5215880889f54734d633099aba7a4d7f9a9c610babbef2efc565c83cc906` |

`sync-bluetooth-soak-02` passed all four checks on this build. Nothing sent a
continuous generated tone through Bluetooth to Samsung, then USB/TCP to the
emulator. All 180 fresh samples per receiver passed from 12:44:47 to 12:48:22
UTC, a 215-second window. Both receivers retained seven or eight clock samples,
despite 15 rejected replies on Samsung and 13 on the emulator during sampling.
There were no probe timeouts, new starts, resyncs, unscheduled frames, relay or
output drops, late frames or inserted silence during that window.

| Receiver | Output error, ms | Clock uncertainty, ms | Startup starts / resyncs / dropped output frames |
|---|---|---|---|
| Samsung | +2.109 to +4.122 | 4.918 to 29.428 | 2 / 1 / 74 |
| Emulator | +5.167 to +5.270 | 5.975 to 44.901 | 1 / 0 / 0 |

Samsung's startup recovery preceded the baseline. An independent check of all
180 observations found fresh reports, advancing non-silent audio and output,
matching prefix hashes, and unchanged recovery counters. This remains a
software timing check, not an acoustic measurement.

`sync-usb-soak-03` also passed all four checks and 180 fresh samples per receiver
on the same build. The emulator sent a continuous generated tone to both
physical phones. The sample window was 12:50:14 to 12:53:49 UTC, 215 seconds.
Both receivers kept eight clock samples with no rejected replies or probe
timeouts. Independent checks found no new recovery, unscheduled frames,
drops, late frames or inserted silence during sampling.

| Receiver | Output error, ms | Clock uncertainty, ms | Startup starts / resyncs / dropped output frames |
|---|---|---|---|
| Nothing | +13.685 to +14.282 | 2.163 to 4.485 | 3 / 2 / 142 |
| Samsung | -7.806 to -6.030 | 1.538 to 2.888 | 2 / 1 / 75 |

`sync-mixed-music-09` stopped at the foreground guard before any test interaction.
A read-only follow-up found Samsung unlocked at its launcher. The new
`sync-mixed-music-10` run passed all four checks with the generated MP3 opened
in Nothing's installed music app. The original failed setup remains retained.
`sync-samsung-music-04` passed the same checks with Samsung as the sender.

| Music-app sender / receiver | Eight-sample window, UTC | Output error, ms | Clock uncertainty, ms |
|---|---|---|---|
| Nothing / Samsung | 12:56:20 to 12:56:29 | -12.933 to -12.370 | 8.854 to 14.855 |
| Nothing / emulator | 12:56:20 to 12:56:29 | +3.480 to +3.530 | 10.549 to 29.720 |
| Samsung / Nothing | 12:58:22 to 12:58:30 | +11.269 to +11.702 | 8.653 to 20.325 |
| Samsung / emulator | 12:58:22 to 12:58:30 | +2.499 to +2.594 | 11.254 to 27.147 |

Both directions kept seven or eight clock samples and advancing non-silent
audio with no new recovery or drops during sampling. The physical receiver
had already calibrated before each baseline: two resyncs and 142 discarded
frames with Nothing sending, one resync and 74 discarded frames with Samsung
sending. Emulator receivers had no startup resyncs or output drops. These are
short generated-file checks, not sustained provider-catalog tests.

`product-nearby-07` passed all four checks from 12:59:31 to 13:01:06 UTC.
Samsung's internal app created a room, opened nearby audio and sent the
generated tone to the emulator. The saved playing receiver reported +2.928 ms
output error, +0.553 ms start error, 1.314 ms clock uncertainty, eight samples
and no resyncs or dropped output frames. Back returned to the room and the
runner verified CaptureService was absent before force-stop cleanup. The
receiver then had zero peers, no ready clock and an empty output queue. Its
stop-time queue invalidation is separate from the playing observation; the
saved source snapshot intentionally predates Back.

`sync-generated-regression-02` passed all six original safety checks on this
build: authenticated USB/TCP, consent cancellation, allowed capture with
matching PCM, blocked-source silence, receiver background reconnect and
capture stop. A `QuietPlayback` wrapper kept both test devices at low volume
until the runner had stopped its apps.

All five CI jobs passed at `a4744a7`, which contains the tested runtime and its
regression tests. The later checkpoint updates change documentation only.
Final read-only checks found no active media projections or test-app processes,
no product capture service and no owned ADB mappings. Media volumes returned
to their prior values. The isolated local API was stopped; its database at
`.local-tools/nearby-mobile-20261012.db` is preserved. No music app, older
product package or unrelated app was removed or reset.

Capture Lab is updated on all three devices. The product is updated on Samsung.
Android rejected product updates on Nothing and the emulator because their
older internal packages have a different signing key. Those apps and their
saved guest sessions remain untouched. Replacement needs the owner's approval;
it is not required for the separate Capture Lab tests.

That product-update blocker was resolved later the same day: the matching
existing development signing key was identified, and all three products were
updated in place. Nothing was uninstalled or cleared. The two signer-specific
internal APKs and checksums are recorded in the
[mobile checkpoint](10-mobile-checkpoint.md).

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

1. Measure actual receiver-speaker skew over ten starts and longer playback,
   including route changes, dropout recovery and a third physical Bluetooth
   node. The nominal capture-sample timeline is not hardware-clock drift
   tracking; longer sessions need measurement. The tracked runner continues
   to require fresh non-silent audio and unchanged recovery counters for every
   sample. Failed runs retain their evidence. Do not relax timing limits.
2. Resolve the product source and rights before public capture distribution.
   A local-file test inside the installed music app does not establish rights
   or capture support for its provider catalog. Do not bypass blocked sources.
3. Implement Internet-room prepare/commit and the independent-file timeline
   separately. The internal nearby card does not complete shared Internet
   playback or couple room membership to nearby admission.
4. Complete the release gates below. Keep desktop and unrestricted mesh work
   paused; neither was added in this milestone.

## Play gates remain open

This milestone alone is not a release approval. Public HTTPS/WSS, upload signing,
privacy and account deletion, chat safety, media distribution rights, native
16 KB compatibility, Console declarations and required tester access remain
separate work. See the [mobile checkpoint](10-mobile-checkpoint.md) and
[deployment notes](11-deployment-proposal.md). Do not describe a debug APK or
software clock result as a Play-ready or acoustically synchronized release.
