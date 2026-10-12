# Capture relay device test

Internal diagnostic only. Install the lab and companion source APKs on an
unlocked USB phone and an emulator, then leave the lab visible on both devices.
Keep both devices available for this test. Start with a quiet media volume.

```powershell
python tools/capture_relay_e2e/run.py --adb .local-tools/android-sdk/platform-tools/adb.exe --phone YOUR_PHONE_SERIAL --emulator emulator-5554 --output .local-tools/capture-relay-e2e
```

The runner uses visible Android permission and projection dialogs. It checks
consent cancellation, generated audio capture, matching PCM hashes, receiver
playback-head advancement, blocked-source silence, reconnection, and stop.
Each completed check prints `PASS`. Failures return a nonzero exit code.

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

Host-only tests, with no ADB access:

```powershell
python -m unittest discover -s tools/capture_relay_e2e -v
```

`run.py` is import-safe. `Device` exposes `command`, `launch`, `tap`, `fill_code`,
`consent`, `report`, and `wait_report` for a supervised manual test.
