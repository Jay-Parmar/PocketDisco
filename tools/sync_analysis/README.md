# Phase 0 sync analysis

This command turns timestamp observations from the Android experiment into per-output skew reports. It pairs observations by provider, output, measurement, trial, and start. A pair is valid when the expected number of distinct devices reports a successful timestamp on one shared clock.

The default gate checks licensed-audio acoustic onset on built-in speakers. It passes when at least ten valid starts have a nearest-rank p95 skew of 250 ms or less. Wired and Bluetooth data remain separate. Bluetooth is informational unless selected with `--gate-output bluetooth`.

## Telemetry schema

Each non-empty JSONL line is one device observation. The versioned contract is in `telemetry.schema.json`; the CLI validates it without requiring a third-party package.

```json
{"schema_version":1,"event_type":"acoustic_onset","trial_id":"trial-01","start_id":"start-01","device_id":"pixel-7","provider":"licensed_audio","output_category":"built_in","outcome":"ok","timestamp_ms":1842.5,"clock_id":"recorder-01"}
```

Required common fields:

- `schema_version`: `1`
- `event_type`: `playback_start` or `acoustic_onset`
- `trial_id`, `start_id`, `device_id`, `provider`: non-empty strings
- `output_category`: `built_in`, `wired`, or `bluetooth`
- `outcome`: `ok` or `failure`

An `ok` observation requires a finite, non-negative `timestamp_ms` and a `clock_id`. Every device in a paired start must use the same clock. A failure observation requires `failure_reason` instead:

```json
{"schema_version":1,"event_type":"acoustic_onset","trial_id":"trial-02","start_id":"start-01","device_id":"pixel-7","provider":"licensed_audio","output_category":"built_in","outcome":"failure","failure_reason":"onset not detected"}
```

Write one observation for every expected device and measurement. A device that does not start still needs a failure observation so the failed attempt is counted.

Acoustic onset timestamps must already be measured on a shared capture clock. This tool accepts no audio inputs and performs no audio decoding or extraction. Do not derive onset timestamps from third-party audio.

## Usage

```powershell
python -m tools.sync_analysis phone-a.jsonl phone-b.jsonl --format markdown
python -m tools.sync_analysis -i phone-a.jsonl -i phone-b.jsonl --format json -o report.json
```

Use `--expected-devices` for runs with more than two phones. Repeat `--gate-output` to require more than one output category. `--gate-measurement playback_start` checks estimated server-clock playback timestamps instead of acoustic onset.

Exit code `0` means the selected gate passed, `1` means it failed, and `2` means input validation failed.
