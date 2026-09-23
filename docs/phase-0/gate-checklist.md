# Phase 0 gate checklist

## Use

Evaluate this checklist against one named branch and commit. Use `Pass`, `Fail`,
`Pending`, or `Not applicable`. `Pending` is not a pass.

Documentation, code, external responses, rights evidence, and physical-device
measurements are different artifact classes. A completed document or harness does
not prove that a provider, rights, or device gate passed.

## 1. Operator documents

These files define the work but do not satisfy external or device evidence:

- [x] Provider support request drafts
- [x] Track rights checklist and evidence register template
- [x] Two-phone Media3 test protocol
- [x] Visible YouTube experiment protocol
- [x] Phase 0 gate checklist

## 2. Required code artifacts

Mark these only after inspecting the repository revision under test.

| ID | Code artifact | Status | Repository path and commit | Test result |
|---|---|---|---|---|
| `P0-CODE-01` | Throwaway Android Media3 harness builds and installs on both phones | `Pending` | `experiments/android-phase0` at `3fa6432`; [one-phone smoke probe](device-runs/2026-08-24-nothing-a142.md) | APK builds and installs on one Nothing A142; the second phone is pending |
| `P0-CODE-02` | Native monotonic scheduling, preload, ready, and structured event logging are implemented | `Pass` | `experiments/android-phase0` at `481c4ee` | 18 unit tests passed; lint reported no issues |
| `P0-CODE-03` | The harness loads owned or licensed MP4/AAC from a media origin separate from the control endpoint | `Pending` | `experiments/android-phase0` | Code enforces separate HTTPS media input; licensed device run is pending |
| `P0-CODE-04` | Repeatable skew calculation reports attempts, failures, median, nearest-rank p95, and maximum | `Pass` | `tools/sync_analysis` at `481c4ee` | 19 unit tests passed, including Android playback export pairing |
| `P0-CODE-05` | Separate Android WebView harness keeps the official YouTube IFrame visible and handles readiness and `onAutoplayBlocked` | `Pass` | `experiments/android-phase0` at `09dbc1c`; [control trial smoke probe](device-runs/2026-08-25-nothing-a142-youtube-control.md) | Unit tests and lint passed; app-ID Referer, cue guard, and blocked autoplay were checked on one phone |
| `P0-CODE-07` | YouTube coordinator routes and Android scheduling carry official IDs and timing control only | `Pass` | `tools/phase0_coordinator` and `experiments/android-phase0` at `09dbc1c` | Coordinator tests and Android unit tests passed; two-phone behavior remains a device gate |
| `P0-CODE-06` | No Spotify playback adapter, SDK integration, or synchronized Spotify test is present without written approval | `Pass` | Branch scan at `481c4ee` | No Spotify playback implementation is present |
| `P0-WIN-01` | Windows endpoint enumeration and generated-audio fanout proof are implemented | `Pending` | `experiments/windows-multi-output` | Build and endpoint evidence are pending |

Local validation completed at `2026-08-18T19:01:25Z`. The debug APK was
3,737,189 bytes with SHA-256
`cc4d50ae3514416012cd77d6f917cb847f67f25718f369131aaaeec6b16f4fdc`.
The APK is a local build output and is not committed.

A one-phone physical-device smoke probe completed on `2026-08-24` at `3fa6432`.
Installation, lifecycle, validation, coordinator clock sampling, and partial
YouTube behavior were exercised. No licensed media playback, two-phone start, or
acoustic measurement occurred, so all physical-device gates remain `Pending`.
See the [sanitized run record](device-runs/2026-08-24-nothing-a142.md).

A second one-phone control trial ran on `2026-08-25` at `09dbc1c`. It verified
the app-ID Referer path, cue readiness guard, authenticated control-only trial,
and autoplay-blocked recovery. It did not complete trusted-gesture playback or
two-phone synchronization. See the [control trial record](device-runs/2026-08-25-nothing-a142-youtube-control.md).

## 3. External and rights evidence

| ID | Evidence | Status | Evidence reference | Notes |
|---|---|---|---|---|
| `P0-EXT-01` | Exact Spotify private-room request submitted through an official channel | `Pending` | | A reply may remain pending, but Spotify stays absent |
| `P0-EXT-02` | Exact Apple Music private-room request submitted through an official channel | `Pending` | | A reply may remain pending; Apple integration stays gated |
| `P0-EXT-03` | YouTube guidance request submitted if the experiment remains a roadmap candidate | `Pending` | | Record why submission was deferred if removed after the test |
| `P0-RIGHTS-01` | 3 to 5 exact MP4/AAC tracks have `Verified` composition and recording evidence | `Pending` | | Include asset SHA-256 and planned-use grant |
| `P0-RIGHTS-02` | Rights cover simultaneous on-demand streams to the planned users, territories, and test period | `Pending` | | Do not infer from "royalty-free" |
| `P0-RIGHTS-03` | Media-host storage, encoding, CDN delivery, attribution, and reporting limits are recorded | `Pending` | | |

Written Spotify approval is not required to pass the licensed-audio proof. It is
required before any Spotify synchronization code or test is introduced. An Apple
response is recorded when received and gates any later Apple implementation.

## 4. Physical-device evidence

| ID | Evidence | Status | Evidence reference | Notes |
|---|---|---|---|---|
| `P0-DEV-01` | Two physical Android phones completed at least 10 valid built-in-speaker starts | `Pending` | | Preserve all attempts and failures |
| `P0-DEV-02` | Built-in audible skew uses isolated capture channels and documented calibration | `Pending` | | Player position alone is insufficient |
| `P0-DEV-03` | Built-in audible skew p95 is at most 250 ms using the documented nearest-rank method | `Pending` | | With 10 results, p95 is the maximum |
| `P0-DEV-04` | The same two-phone protocol completed at least 10 valid wired-output starts | `Pending` | | Report separately from built-in |
| `P0-DEV-05` | Bluetooth was characterized separately, or unavailable hardware was recorded | `Pending` | | Best-effort and non-gating |
| `P0-DEV-06` | Logs and network evidence show each phone fetched from the licensed media origin, never the control backend | `Pending` | | Do not retain signed URLs |
| `P0-DEV-07` | All visible YouTube cases were run on two phones and ads, lifecycle, blocked autoplay, and playlist transitions were recorded | `Pending` | | `Not observed` is not proof of absence |
| `P0-DEV-08` | A YouTube outcome was recorded as internal experiment, remove or defer, or foreground best-effort candidate | `Pending` | | Production support remains Phase 4 gated |
| `P0-WIN-02` | Two Windows endpoints completed acoustic onset and drift measurement | `Pending` | | Record Shared Audio and application fanout separately |

## 5. Hard exit criteria

All rows below must be `Pass` before Phase 0 is declared complete.

| Criterion | Required supporting IDs | Status | Decision note |
|---|---|---|---|
| Owned or licensed audio starts within 250 ms built-in-speaker p95 on two phones | `P0-RIGHTS-01`, `P0-RIGHTS-02`, `P0-DEV-01` to `P0-DEV-03` | `Pending` | |
| No PocketDisco backend endpoint sends music bytes | `P0-CODE-03`, `P0-DEV-06` | `Pending` | |
| YouTube is explicitly experimental or removed based on results and guidance status | `P0-DEV-07`, `P0-DEV-08` | `Pending` | |
| Spotify synchronization remains absent unless written approval is attached | `P0-CODE-06`, `P0-EXT-01` | `Pending` | |

## 6. Scope boundary

- Snapshot-based reconnect is not a Phase 0 feasibility gate. The committed build
  plan places reconnect-safe room state in Phase 1 and playback reconnect and
  stale-command recovery in Phase 2.
- Wired results are required characterization, but the committed Phase 0 timing
  exit criterion names built-in speakers.
- Bluetooth is best-effort.
- A provider request, response, or technical capability does not override the
  controlling provider terms.
- Phase 0 does not authorize a complete product scaffold or production launch.

## Signoff

| Field | Value |
|---|---|
| Branch and commit | |
| Evaluation date, UTC | |
| Rights evidence IDs | |
| Media3 evidence IDs | |
| YouTube evidence IDs | |
| Provider request and response IDs | |
| Open failures or restrictions | |
| Phase 0 decision | `Pass / Fail / Pending` |
| Reviewer | |
