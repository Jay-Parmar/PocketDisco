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
| `P0-CODE-01` | Throwaway Android Media3 harness builds and installs on both phones | `Pending` | | |
| `P0-CODE-02` | Native monotonic scheduling, preload, ready, and structured event logging are implemented | `Pending` | | |
| `P0-CODE-03` | The harness loads owned or licensed MP4/AAC from a media origin separate from the control endpoint | `Pending` | | |
| `P0-CODE-04` | Repeatable skew calculation reports attempts, failures, median, nearest-rank p95, and maximum | `Pending` | | |
| `P0-CODE-05` | Separate Android WebView harness keeps the official YouTube IFrame visible and handles readiness and `onAutoplayBlocked` | `Pending` | | |
| `P0-CODE-06` | No Spotify playback adapter, SDK integration, or synchronized Spotify test is present without written approval | `Pending` | | |

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
