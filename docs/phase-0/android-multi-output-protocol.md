# Android multi-output probe protocol

## Purpose

This probe determines whether a target Android device can play controlled test
audio through more than one local output. It does not promise support for every
phone or pair of Bluetooth headphones.

The probe uses a generated PCM click signal. It does not capture or duplicate
audio from YouTube or another provider-controlled player.

## Required equipment

- One physical Android phone running the Phase 0 harness
- Two paired output devices
- Two isolated microphone channels or another calibrated acoustic capture setup
- ADB access for sanitized logs

The Android emulator can test the screen, scheduling, and unsupported state. It
cannot provide Bluetooth routing or acoustic evidence.

## Procedure

1. Open the Android multi-output probe and refresh the output list.
2. Record the Android version and reported LE Audio capabilities.
3. If Android or the device vendor offers audio sharing, create the group in the
   system UI and run the system-group mode first.
4. Select two distinct outputs for the experimental dual-track mode.
5. Schedule the generated click signal at least five seconds ahead.
6. Confirm the actual routed device list after playback begins. A successful
   preferred-device request alone is not a pass.
7. Repeat 20 starts and keep failed, single-route, and route-lost attempts.
8. Continue playback for ten minutes and record drift, underruns, and route
   changes.
9. Measure onset and final drift from the isolated acoustic capture.
10. Export sanitized telemetry without Bluetooth addresses or device names.

## Result states

- `unsupported`: the platform exposes no usable shared route.
- `single_route`: both tracks resolved to one output or only one output played.
- `distinct_routes`: Android reported two distinct active routes.
- `measured`: distinct routes were confirmed by acoustic capture.
- `route_lost`: an active output disappeared during the run.

Only `measured` is evidence of local acoustic synchronization. Results apply to
the exact phone, Android build, Bluetooth hardware, and output devices tested.
