# Nothing A142 Android multi-output smoke probe

## Run identity

| Field | Value |
|---|---|
| Date, UTC | 2026-09-23 |
| Branch | `feature/android-multi-output-probe` |
| Code revision | `0f8c5ea` |
| Device | Nothing A142 |
| Android | 16, API 36 |
| APK size | 3,786,259 bytes |
| APK SHA-256 | `d5b3a5dc719843f576df114483f06b2fddb3a4c808b7ec88be9152162dcd9207` |

No device serial, Bluetooth address, output name, or account identifier is
included in this record.

## Automated checks

- Android unit tests: 44 passed
- Android lint: passed
- Debug APK assembly: passed
- Sync analysis tests: 19 passed
- Coordinator tests: 25 passed

## Physical smoke result

- ADB install and launch passed.
- The probe reported three Android output entries and no direct fanout targets.
- LE Audio support reported `not_supported`.
- LE Audio broadcast-source support reported `not_supported`.
- The generated 48 kHz PCM click started on the current system route.
- Android reported one active route, ID `3`, with zero underruns.
- The probe reported `system_group_unverified`, as required for one logical
  route.
- No PocketDisco process crash appeared in the filtered Android runtime log.

## Result

The capability, scheduling, playback, route observation, and underrun paths work
on this phone. This run does not prove multi-output playback. Two direct media
outputs were not connected, and no isolated acoustic capture was made.

`P0-OUT-01` passes as an implementation gate. `P0-OUT-02` remains pending until
two physical outputs report distinct active routes and acoustic skew is measured.
