# Mixed-platform generated-signal protocol

## Purpose

This protocol checks whether one Phase 0 coordinator trial can schedule a
generated signal on a physical Android phone, an Android emulator, and Windows.
It measures control and player timing separately from acoustic output.

The coordinator carries identifiers and timing only. It never carries audio
bytes. This protocol does not capture, extract, transmit, or duplicate YouTube
or other provider-controlled audio.

## Participants

- One physical Android phone using the generated-signal output probe.
- One Android emulator using its emulated system output.
- One Windows process controlling the selected local render endpoints.

Windows is one client for command and player timing even when it fans out to two
endpoints. Each Windows endpoint becomes a separate acoustic output only when an
isolated capture channel measures it. Emulator results are never acoustic
evidence.

## Control contract

Use the existing Phase 0 HTTP coordinator without adding routes:

- `GET /v1/time` for seven clock samples per client.
- `POST /v1/trials` to create the shared control record.
- `GET /v1/trials/{id}` to fetch the same control record.

Each client selects the three samples with the lowest network round-trip time,
uses their median server-to-monotonic offset, and records its estimated clock
uncertainty. The effective server time is converted directly to the platform's
monotonic clock. A local wall clock must not be used as the scheduling clock.

The trial identifies `generated-click-v1`. Its digest is the SHA-256 of the
canonical one-second, 48 kHz, mono, signed-16 little-endian PCM frame. Android
and Windows must pass the same golden digest test before a device run.

## Preparation

1. Use one named branch and commit on all three clients.
2. Run the Android, Windows, coordinator, and sync-analysis tests.
3. Start the coordinator on a trusted, isolated LAN and keep its token out of
   commands, logs, telemetry, screenshots, and committed files.
4. Confirm the physical phone and emulator can reach the coordinator.
5. Preselect safe output volume and the intended routes.
6. Prepare every player before creating or fetching the time-limited trial.
7. Record sanitized client labels and run-local output labels only.
8. Open an operator attempt ledger before the first trial is created.

## Trial procedure

1. Take seven coordinator time samples on each client.
2. Confirm the generated signal identity and digest on each client.
3. On both Android clients, tap `Prepare selected coordinator output` and
   confirm that each reports ready on the intended route.
4. Create one trial with 25 seconds of lead after all clients report ready.
5. Fetch and validate that trial immediately on the other clients. Android
   reuses its prepared output only while the selected route is unchanged and
   the player remains prepared.
6. Reject the attempt if any client has less than its minimum monotonic lead.
7. Record one `command_issued` observation per client.
8. Record one `playback_observed` observation per client only when the platform
   reports the player active. Do not infer this event from the command.
9. If isolated microphone channels are available, record `acoustic_onset`
   separately for each captured output.
10. Preserve every attempt, including validation, route, timeout, and playback
    failures.
11. Repeat at least ten valid starts before reporting timing percentiles.

Timing telemetry v2 starts only after a client has resolved and validated a
trial. It does not replace the operator attempt ledger for earlier preparation,
readiness, clock, create, fetch, or validation failures. Record those with an
attempt number, stage, stable failure reason, outcome, and optional trial UUID.
Do not record coordinator URLs, credentials, endpoint IDs, response bodies, or
raw exception messages.

## Analysis

Group records by scenario, measurement type, trial, and start. Require the
explicit client set for command and player timing. Require a separate explicit
output set for acoustic timing when capture is available.

For each measurement type, report attempts, failures, median skew,
nearest-rank p95 skew, maximum skew, target error, and maximum clock
uncertainty. Do not subtract uncertainty from measured values. Do not combine
command, player, and acoustic results.

Scenario analysis has no Phase 0 performance gate. The existing two-physical-
phone acoustic gate remains authoritative.

## Result limits

- Command timing shows when each client issued its playback command.
- Player timing shows when each platform reported active playback.
- Acoustic timing requires isolated external capture channels.
- Emulator playback cannot satisfy a physical-device or acoustic gate.
- Windows application fanout does not prove two Bluetooth headphones work.
- A successful generated-signal run does not authorize provider-audio capture
  or redistribution.
