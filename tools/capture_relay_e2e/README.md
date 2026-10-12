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

The transport is phone loopback -> ADB -> PC loopback -> emulator. It does not
test physical Bluetooth, radio range, acoustic output, or audible synchronization.
Port 18990 must not have an existing ADB forward. The runner refuses conflicts
and removes only the mapping it created. It force-stops only the two diagnostic
packages at the start and end, without clearing their data or changing settings.
An unexpected foreground app stops UI interaction.

`--music-file` runs a separate generated MP3-file check in the already installed
music app. It opens no catalog content and grants no music-app permissions.
The music app must already allow local audio access. Cleanup uses Back from the
local preview, never force-stop or data clearing. This result does not establish
capture permission for other content or provider approval. Use the source APK
that bundles the generated MP3, not an earlier WAV-only build.

Reports contain diagnostic counters and hashes, not PCM, session codes, device
serials, screenshots, or saved UI hierarchies. Output must stay inside the repo.
The app-private report can be a partial write; polling retries JSON parse failures.
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
