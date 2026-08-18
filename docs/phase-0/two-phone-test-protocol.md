# Two-phone Media3 test protocol

## Purpose

Measure whether two physical Android phones can independently play the same
owned or licensed MP4/AAC asset on a shared future timeline. Built-in speakers
are the Phase 0 exit measurement. Wired output is a required separate
characterization. Bluetooth is best-effort and never averaged into either result.

This is a throwaway feasibility harness, not the complete PocketDisco app.

## Required repository artifact before a run

The harness must:

- use AndroidX Media3 on both phones;
- fetch audio directly from the licensed media host, not the control backend;
- preload one verified asset and report ready or error;
- estimate server time from the Android monotonic clock;
- schedule playback for a future server timestamp with an initial 2 to 4 second
  lead;
- log structured prepare, ready, scheduled, playing, position, ended, and error
  events;
- avoid logging tokens, signed URLs, or personal account data.

The run is invalid if either app relies on a JavaScript timer for the scheduled
start.

## Equipment and prerequisites

- Two physical Android phones, labeled `A` and `B`.
- Ordinary Wi-Fi with both phones on the same access network.
- The exact phone model, Android build, app build, Media3 version, and WebView
  version where applicable.
- One selected asset with `Verified` rights status in
  [track-rights-register.md](track-rights-register.md).
- A stereo recorder or audio interface that can isolate phone A and phone B onto
  separate channels.
- A team-owned calibration track with an unambiguous onset, or a licensed track
  whose unmodified onset can be measured. Do not alter a track unless its rights
  allow that use.
- Built-in-speaker output and one wired-output route available on each phone.
- Optional Bluetooth devices, with model and codec recorded when visible.

For a built-in-speaker run, place both phones at equal distance and orientation
to their assigned recorder channels. For a wired run, route each phone to an
isolated input. Verify that capture-channel latency is equal or measure and apply
the fixed difference before the run.

## Run record

| Field | Value |
|---|---|
| Evidence ID | `P0-M3-[date]-[sequence]` |
| Date and operator, UTC | |
| Branch and commit | |
| Harness build ID | |
| Track ID and SHA-256 | |
| Media origin hostname | Never record a signed URL |
| Control endpoint hostname | |
| Phone A model, Android build | |
| Phone B model, Android build | |
| Media3 version | |
| Network and access point | Do not record a Wi-Fi password |
| Output path | `built-in / wired / Bluetooth` |
| Wired device or Bluetooth model and codec | |
| Capture equipment and sample rate | |
| Capture-channel calibration | |
| Planned valid trials | Minimum 10 |

## Device log fields

Use JSON Lines or another machine-readable format. Each event should contain:

```text
run_id, trial_id, device_label, app_build_id, asset_id, asset_sha256,
output_path, event, client_elapsed_realtime_ns, estimated_server_time_ms,
clock_rtt_ms, clock_offset_ms, clock_uncertainty_ms,
effective_at_server_time_ms, requested_position_ms, reported_position_ms,
playback_state, error_code
```

Record the media-origin hostname separately from the control endpoint. Strip URL
queries and headers before saving logs.

## Setup

1. Confirm the rights row is `Verified` and copy its evidence IDs into the run
   record.
2. Confirm that the media URL resolves to the licensed media host and that no
   control-backend route returns media bytes.
3. Put both phones in foreground use, disable Bluetooth for built-in and wired
   scenarios, and record volume and audio-route settings.
4. Close other media apps and record any power-saving or audio-enhancement mode.
5. Calibrate the capture channels and preserve the calibration result.
6. Start device logs and external audio capture before the first prepare.

## Trial procedure

Run at least 10 valid trials for built-in speakers, then at least 10 valid trials
for wired output.

1. Create a new `trial_id`. Never reuse an ID after a failed attempt.
2. Each phone takes seven clock samples. Record RTT, offset, and uncertainty.
3. Each phone prepares the same asset at position 0 and reports ready only after
   it can start without an expected network stall.
4. The coordinator selects one effective server time 2 to 4 seconds in the
   future. Record that exact value on both phones.
5. Each native harness maps the effective server time to its monotonic clock and
   schedules playback.
6. Let the asset play long enough to capture the onset and stable reported
   positions. Ten seconds is sufficient unless the chosen onset requires more.
7. Stop and reset both players. Preserve both device logs and the capture before
   starting the next trial.
8. Record cold or warm cache state. Do not discard a cold start or failed start.

Continue until there are at least 10 valid trials, but report every attempt. A
failure is not converted into a valid trial or omitted from the failure count.
The Android telemetry export is cumulative. Retain the latest export from each
phone as the session snapshot and do not combine earlier snapshots from the same
phone. If a scheduled start produces no terminal event, record a failure on each
phone before exporting.

## Audible-onset analysis

1. On each isolated capture channel, identify the same first unambiguous waveform
   feature from the unmodified asset.
2. Record its sample index and convert it to milliseconds using the capture sample
   rate.
3. Apply only the capture-channel calibration recorded before the run.
4. Calculate trial skew as `abs(onset_A_ms - onset_B_ms)`.
5. Keep operator exclusions in the raw table with a reason. Equipment failure and
   playback failure are different categories.

For each output path, publish valid count, attempted count, failed-start count,
median, p95, maximum, clock uncertainty, and any exclusion count. Calculate p95
with the nearest-rank method: sort `n` results and select rank `ceil(0.95 * n)`.
With 10 valid trials, p95 is the largest result.

Do not substitute player-reported position for audible onset. Report playhead
skew separately at matched estimated server times.

## Result table

| Trial | Output | Cache | RTT A/B ms | Uncertainty A/B ms | Onset A ms | Onset B ms | Audible skew ms | Playhead skew ms | Result or failure |
|---:|---|---|---|---|---:|---:|---:|---:|---|
| 1 | | | | | | | | | |

| Output | Attempts | Valid | Failed starts | Median ms | p95 ms | Max ms | Exclusions | Gate interpretation |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| Built-in | | | | | | | | |
| Wired | | | | | | | | |
| Bluetooth | | | | | | | | `Best-effort, non-gating` |

## Bluetooth best-effort run

If suitable hardware is available, repeat the same procedure without changing
the built-in or wired dataset. Record each Bluetooth device and codec separately.
Do not combine Bluetooth results across codecs, accessories, or output paths. If
hardware is unavailable, record that fact rather than treating Bluetooth as
passed.

## Phase 0 interpretation

- Built-in-speaker p95 at or below 250 ms passes the provisional timing exit
  criterion when there are at least 10 valid trials and all attempts are reported.
- Wired results are reported separately against the same provisional target, but
  the committed Phase 0 exit criterion names built-in speakers.
- Bluetooth is best-effort and cannot fail the Phase 0 gate.
- A passing timing result does not pass the gate if rights evidence is incomplete
  or the control backend transported audio.
- Reconnect and snapshot recovery remain later room-product work. They are not
  added to this throwaway timing protocol.

## Evidence bundle

The sanitized bundle should include the run record, rights evidence IDs, raw
device logs, capture calibration, onset measurements, aggregate calculation,
failures, and operator conclusion. Record large or sensitive raw captures by
checksum and secure reference rather than committing them to Git.
