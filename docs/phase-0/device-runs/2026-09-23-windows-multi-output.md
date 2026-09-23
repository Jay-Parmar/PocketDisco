# Windows multi-output probe: 2026-09-23

Evidence ID: `P0-WIN-20260923-01`

## Scope

This run tested application-managed playback of one generated click track on
two Windows render endpoints. It did not capture provider audio and did not send
audio over the PocketDisco control plane.

## Revision and environment

| Field | Value |
|---|---|
| Branch | `feature/windows-multi-output-probe` |
| Tested code commit | `cc82677` |
| Windows version | 25H2, build 26200.9457, x64 |
| .NET SDK | 10.0.401 |
| Route mode | `app_fanout` |
| Output classes | One USB render endpoint and one built-in render endpoint |
| Signal | Generated 48 kHz mono PCM click track |
| Application volume | 12 percent |

Hardware names and Windows endpoint IDs were used only for interactive
selection. They are not stored in the run files.

## Procedure

- Enumerated active endpoints immediately before the run.
- Ran 20 independent two-second trials with a five-second scheduled lead.
- Ran one 600-second stability trial with a five-second scheduled lead.
- Required both players to open before scheduling the shared timeline.
- Required both players to raise `MediaEnded` before recording completion.
- Preserved each trial in a separate NDJSON file with run-local output labels.

The run window was 2026-09-23 16:23:54 UTC through 16:39:44 UTC.

## Results

| Measurement | Result |
|---|---:|
| Independent trials | 20 |
| Valid trials | 20 |
| Failed trials | 0 |
| `output_ready` records | 40 of 40 |
| `playback_start_command` records | 40 of 40 |
| `playback_completed` records | 40 of 40 |
| Starts requiring a late-position seek | 0 |
| Command error minimum | 0 ms |
| Command error median | 3 ms |
| Command error nearest-rank p95 | 11 ms |
| Command error maximum | 12 ms |

The stability trial issued the shared resume command 10 ms after its target.
After 600.088 seconds, both players had raised `MediaEnded`, and the process
exited successfully. No player or controller failure was reported.

All 21 files use only `output-1` and `output-2`. A privacy scan found no saved
hardware names, endpoint IDs, or Windows endpoint paths.

## Interpretation

`P0-WIN-01` passes for this revision. The probe enumerated endpoints, opened a
player on each selected endpoint, issued playback from one shared timeline, and
observed both players complete.

Command error measures when the shared resume command was issued. `MediaEnded`
is player-state evidence. Neither measurement proves audible onset, acoustic
skew, or drift.

`P0-WIN-02` remains pending because this setup did not have isolated microphone
channels. Windows Shared Audio was not confirmed, and a second active Bluetooth
render endpoint was not available. The result does not prove two-headphone
Bluetooth fanout.

Earlier provisional Classic Bluetooth plus built-in endpoint runs are retained
in [the original run directory](2026-09-23-windows-multi-output/) and described
in the [development checkpoint](../../checkpoints/2026-09-23-development-pause.md).
They used older completion semantics and are not counted in the results above.

## Evidence files

The sanitized raw records are in
[`2026-09-23-windows-multi-output-v2/`](2026-09-23-windows-multi-output-v2/).
