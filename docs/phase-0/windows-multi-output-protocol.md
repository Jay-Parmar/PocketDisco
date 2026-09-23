# Windows multi-output probe protocol

## Purpose

This probe determines whether the target Windows PC can play controlled test
audio through two local render endpoints. It does not promise that every
Bluetooth adapter or pair of headphones can sustain two streams.

The probe generates a WAV click signal. It does not capture or duplicate audio
from YouTube or another provider-controlled player.

## Modes

- `system_group`: Windows Shared Audio or another system-managed route receives
  one application stream.
- `app_fanout`: PocketDisco opens one player per selected endpoint and drives
  them from one timeline.

## Procedure

1. Run the endpoint list command and assign run-local labels to the intended
   outputs. Do not save endpoint IDs or hardware names in evidence.
2. If Windows exposes Shared Audio, configure two compatible LE Audio devices in
   Quick Settings and run the system-group case.
3. Select two distinct active endpoints for the application-fanout case.
4. Set both outputs to a safe volume.
5. Schedule the generated click at least five seconds ahead.
6. Record readiness, start command time, player failures, and endpoint removal.
7. Repeat 20 starts and retain all failures.
8. Continue playback for ten minutes and record final drift.
9. Measure each output through an isolated microphone channel.

## Result states

- `unsupported`: Windows or the audio hardware cannot open the requested mode.
- `partial`: one selected endpoint failed or disappeared.
- `started`: all players started from the shared timeline.
- `measured`: isolated acoustic capture produced onset and drift results.

Only `measured` is evidence of acoustic synchronization. Results apply to the
exact Windows build, adapter, driver, codec, and outputs tested.
