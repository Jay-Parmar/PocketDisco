# Mixed-platform generated signal run: 2026-09-23

Evidence ID: `P0-MIX-20260923-01`

## Scope

This run scheduled the same generated signal on one physical Android phone, one
Android emulator, and one Windows process controlling two local render
endpoints. It measured command and player-state timing. It did not capture
acoustic onset, provider audio, or Bluetooth output.

The coordinator carried signal identity and timing only. No music bytes crossed
the control plane.

## Revision and environment

| Field | Value |
|---|---|
| Branch | `feature/mixed-platform-sync-probe` |
| Tested code commit | `d977dad` |
| Scenario | `mixed-20260924-01` |
| Run window, UTC | 2026-09-23T19:00:06.136Z to 2026-09-23T19:25:47.184Z |
| Physical Android | Nothing A142, Android 16, API 36, current system route |
| Emulator | Android 16, API 36, headless, virtual system route |
| Windows | Windows 11 25H2, build 26200.9457, x64, .NET SDK 10.0.401 |
| Windows route | Application fanout to one USB and one built-in render endpoint |
| Signal | `generated-click-v1`, 48 kHz mono signed 16-bit PCM |
| Signal SHA-256 | `e3c4db9cce24fdeb8cfc9131f0240665c54afde2519d99a59b91db98602274f0` |
| APK size | 3,815,784 bytes |
| APK SHA-256 | `94fb9964473a8deb7635b2c2f866cda230ddb37bc87685f967027c6623d6f3f6` |

No device serial, endpoint ID, Bluetooth address, account identifier, or
coordinator credential is stored in the evidence.

## Automated checks

- Android unit tests: 83 passed
- Android lint: passed with one unchanged unused-resource warning
- Android debug APK assembly: passed
- Windows Release tests: 178 passed
- Windows formatting check: passed
- Coordinator tests: 25 passed
- Sync analysis tests: 51 passed

## Procedure

- Took seven coordinator time samples on each client before every start.
- Prepared both Android system-route players before Windows created the trial.
- Required both Windows players to be ready before creating a trial with 25
  seconds of lead.
- Fetched the same trial on both Android clients and required at least five
  seconds of remaining monotonic lead.
- Recorded one `command_issued` and one `playback_observed` observation per
  client and trial.
- Repeated ten starts. There were no failed or retried trial attempts.
- Stopped the coordinator and invalidated its temporary token after export.

The physical phone used the LAN coordinator address. The emulator used a local
ADB reverse tunnel because its virtual-network HTTP path added about 800 ms of
round-trip delay. The tunnel reduced measured clock uncertainty enough for the
control-plane run.

## Results

| Measurement | Valid | Failed | Median skew | p95 skew | Maximum skew | Target-error p95 | Max clock uncertainty |
|---|---:|---:|---:|---:|---:|---:|---:|
| Command issued | 10 | 0 | 33.5 ms | 111 ms | 111 ms | 75 ms | 47 ms |
| Playback observed | 10 | 0 | 511.5 ms | 586 ms | 586 ms | 559 ms | 47 ms |
| Acoustic onset | 0 | 0 | Not measured | Not measured | Not measured | Not measured | Not measured |

All 60 expected records were present. Every trial had six records, all outcomes
were `ok`, and every record carried the same generated signal identity and
digest.

## Interpretation

The mixed-platform protocol completed ten valid control and player-state starts.
Command timing was substantially tighter than player-state observation timing.
The player result reflects when each platform reported active playback and must
not be treated as audible onset.

Windows counts as one timing client even though the process controlled two
render endpoints. The emulator is protocol-only evidence. This run does not
prove audible synchronization, two Bluetooth headphones, Android multi-output,
or seamless listening.

The Phase 0 acoustic and rights gates remain pending. They still require two
physical Android phones, isolated microphone channels, and authorized test
audio under the committed plan.

## Evidence files

The sanitized records, operator attempt ledger, and generated reports are in
[`2026-09-23-mixed-platform-sync/`](2026-09-23-mixed-platform-sync/).
