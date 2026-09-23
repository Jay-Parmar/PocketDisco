# Development checkpoint: 2026-09-23

## Repository state

- Active branch: `feature/windows-multi-output-probe`
- Windows feature head before this checkpoint: `d460320`
- Base branch: `phase/0-feasibility` at `608eaf8`
- Existing pull requests: Phase 0 PR 1 and Android multi-output PR 2
- No Windows pull request has been opened
- Nothing from this checkpoint has been pushed

## Windows work completed

- Added a .NET 10 command-line feasibility probe.
- Enumerated active Windows render endpoints without saving endpoint IDs.
- Added validated two-endpoint selection.
- Generated a deterministic 48 kHz mono PCM click track.
- Added monotonic future-start planning and late-start position calculation.
- Opened one `MediaPlayer` per endpoint under one `MediaTimelineController`.
- Added safe 12 percent application volume.
- Added sanitized NDJSON telemetry and a Windows CI job.
- Passed 22 tests with zero build warnings.
- Opened a USB output plus built-in speakers in one smoke run.
- Opened one Classic Bluetooth output plus built-in speakers in one smoke run.

Twenty provisional Bluetooth-plus-speaker command trials completed without a
process failure. Their shared start-command error was 0 ms minimum, 3.5 ms
median, 8 ms nearest-rank p95, and 10 ms maximum. These trials used a one-second
lead, so they do not satisfy the protocol's five-second lead requirement.

A 600-second Bluetooth-plus-speaker process run also returned successfully with
a five-second lead. Its command error was 5 ms. The run files are stored beside
this checkpoint under
`docs/phase-0/device-runs/2026-09-23-windows-multi-output/`.

## Evidence limits found during review

The current Windows telemetry calls the final timer event `playback_completed`
without observing `MediaEnded` or playback state. Treat that event only as the
end of the requested dwell period. It is not proof that either endpoint rendered
the entire signal.

A `MediaFailed` event that arrives after readiness but before the target can be
recorded without preventing the later timeline resume command. This race must be
fixed before the branch is ready for review.

The saved Windows runs prove endpoint discovery, media open, shared command
issuance, and absence of a reported failure during the process window. They do
not prove audible onset, acoustic skew, drift, or two-Bluetooth-device support.
Those require isolated microphone channels and a second active Bluetooth render
endpoint.

## Android state

Android PR 2 remains open from `feature/android-multi-output-probe`. An isolated
worktree at `.local-tools/worktrees/android-fixes` contains four unpushed review
fix commits:

- `1e36b31 fix: reject overlapping Android output routes`
- `e9f3d5a fix: keep Android output probe awake`
- `1997c06 fix: detect transient Android route loss`
- `35a3a61 fix: recover failed Android playback starts`

The agent was stopped before the remaining review fixes and final test loop.
Still required:

- Split issued playback commands from observed playback starts.
- Preserve Bluetooth-disabled and raw platform error states.
- Run unit tests, lint, build, physical-phone smoke, and emulator smoke.
- Push the Android branch only after those checks pass.

## Emulator state

The API 36 emulator boot issue was fixed locally by disabling the three
`firstboot.*Snapshot` flags and cold booting with `-no-snapshot`. The APK
installed, both activities rendered, system-route playback used the emulated
speaker with zero underruns, and no crash or ANR was observed. The emulator
exposes no Bluetooth outputs. It was shut down at this checkpoint. The physical
Nothing phone remains connected.

## Resume order

1. Fix the two Windows review blockers and add regression tests.
2. Repeat the Windows 20-start run with at least five seconds of lead.
3. Record playback-state evidence honestly and keep acoustic gates pending.
4. Finish and validate the two remaining Android review fixes.
5. Update the Windows run record, README, and Phase 0 gate checklist.
6. Push the Windows and Android branches and refresh their pull requests.
7. Continue with the separate mixed Android, emulator, and Windows sync branch.
