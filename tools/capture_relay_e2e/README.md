# Capture relay device test

Internal diagnostic only. Install the lab and companion source APKs on an
unlocked USB phone and an emulator, then leave the lab visible on both devices.
Keep both devices available for this test. Start with a quiet media volume.
Use Python 3.9 or newer; the runner has no third-party Python dependencies.
Build and installation details are in [the lab notes](../../experiments/android-capture-relay/README.md).

```powershell
python tools/capture_relay_e2e/run.py --adb .local-tools/android-sdk/platform-tools/adb.exe --phone YOUR_PHONE_SERIAL --emulator emulator-5554 --output .local-tools/capture-relay-e2e
```

The runner uses visible Android permission and projection dialogs. It checks
consent cancellation, generated audio capture, matching PCM hashes, receiver
playback-head advancement, blocked-source silence, reconnection, and stop.
Each completed check prints `PASS`. Failures return a nonzero exit code.
These are the intended checks, not a statement that a particular run passed.
Review that run's `results.json`, including failure entries and the transport.
Use a fresh output directory for every run to preserve earlier failure evidence.
Each passed check saves deep-copied `latestObservations` from both devices, so
later reconnects do not replace earlier hash evidence. These are the latest
already-read reports, not new or simultaneous device readings. `recordedAtUtc`
marks when the snapshot was copied; run start and finish times are also UTC.
The top-level `phone` and `emulator` fields contain only the final observations.

The transport is phone loopback -> ADB -> PC loopback -> emulator. It does not
test physical Bluetooth, radio range, acoustic output, or audible synchronization.
Port 18990 must not have an existing ADB forward. The runner refuses conflicts
and removes only the mapping it created. It force-stops only the two diagnostic
packages at the start and end, without clearing their data or changing settings.
An unexpected foreground app stops UI interaction.
Failure messages identify known test, system, and launcher packages only;
unrelated foreground packages are reported as `another app`.

`--music-file` runs a separate generated MP3-file check in the already installed
music app. First it checks UID isolation: capture selects the music app while
the companion source plays an allowed tone, and capture must remain silent.
It then opens the generated MP3 in the music app and checks matching PCM and
receiver playback progress. This mode skips the blocked-source and receiver
reconnect checks. It opens no catalog content and grants no music-app permissions.
The music app must already allow local audio access. Cleanup uses Back from the
local preview, never force-stop or data clearing. This result does not establish
capture permission for other content or provider approval. Use the source APK
that bundles the generated MP3, not an earlier WAV-only build.

Reports contain diagnostic counters and hashes, not PCM, session codes, device
serials, screenshots, or saved UI hierarchies. Output must stay inside the repo.
Current app-private reports use atomic writes; polling also tolerates an
unavailable report or a JSON parse failure while an older build is running.
The `first100AudibleFramesSha256` field hashes non-silent PCM frames. Its name
does not imply that a microphone measured audible output.

Host-only tests, with no ADB access:

```powershell
python -m unittest discover -s tools/capture_relay_e2e -v
```

The capture-lab CI runs these tests without ADB. CI success does not establish
phone, Bluetooth or acoustic results.

`run.py` is import-safe. `Device` exposes `command`, `launch`, `tap`, `fill_code`,
`consent`, `report`, and `wait_report` for a supervised manual test.

## Scheduled receiver check

`sync_run.py` uses one source and two receivers with protocol-v2 builds. It
temporarily lowers media volume to at most 2, stops the owned test apps before
restoring the previous volume, and preserves volume changes made during a run.
It refuses busy ADB ports and unexpected foreground apps.

For an emulator source and two physical USB receivers:

```powershell
python tools/capture_relay_e2e/sync_run.py --adb .local-tools/android-sdk/platform-tools/adb.exe --source emulator-5554 --receiver PHONE_A --receiver PHONE_B --transport usb --output .local-tools/sync-usb-new
```

For a paired physical Bluetooth link followed by a USB hop to the emulator:

```powershell
python tools/capture_relay_e2e/sync_run.py --adb .local-tools/android-sdk/platform-tools/adb.exe --source PHONE_A --receiver PHONE_B --receiver emulator-5554 --transport mixed --peer-name "Phone A paired name" --music-file --output .local-tools/sync-mixed-new
```

The mixed path is A -> Bluetooth -> B -> ADB loopback TCP -> emulator.
Pair A and B first. `--music-file` opens only the bundled generated MP3 in the
already installed music app. Its local-audio permission must be settled by
the owner before running; this runner does not grant it. Omit the flag to use
the generated-tone source.

The four checks cover two authenticated peers, scheduled output on both
receivers, matching non-silent PCM prefixes, and eight fresh advancing timing
samples per receiver by default. Each sample requires new non-silent captured
and received frames, advancing output, and unchanged recovery/drop counters
against the post-audio baseline. A lost clock, changed peer count, extra start,
resync, late frame, inserted silence or drop fails the run even if it recovers.

Use `--samples` from 8 to 600 for a bounded sustained test. For roughly three
minutes of continuous generated tone:

```powershell
python tools/capture_relay_e2e/sync_run.py --adb .local-tools/android-sdk/platform-tools/adb.exe --source emulator-5554 --receiver PHONE_A --receiver PHONE_B --transport usb --samples 180 --output .local-tools/sync-usb-sustained-new
```

The runner waits one second between samples; ADB reads and bounded report waits
add time. More than eight samples requires the continuous generated-tone
source, so combining `--music-file` with a larger count is rejected before
device work. The generated MP3 is finite, not a long-test source.

Results retain the baseline, per-check observations and sampled error,
uncertainty, non-silent frame counts, output progress and drop counters. On
failure, partial samples are kept. Final read-only device reports are refreshed
before cleanup so source errors are retained too; a failed read is recorded
without skipping the other devices or cleanup. Retained start-error values are
not independent start trials. Prefix hashes do not verify every frame.

This checks software timing windows, not acoustic alignment, Bluetooth
range, three physical radio nodes, catalog music or synchronization of the
sender's original speaker. The public release contains no capture engine.
