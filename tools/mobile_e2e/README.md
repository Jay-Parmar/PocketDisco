# Android room smoke test

Runs the internal app through its visible UI on one USB phone and one emulator.
Start the local API, install the internal APK, and reverse port 8000 on each
device first. Unlock the phone and leave it available during the run.

From the repository root:

```powershell
python tools/mobile_e2e/run.py --adb .local-tools/android-sdk/platform-tools/adb.exe --output .local-tools/mobile-e2e
```

For a remote API reached through an SSH tunnel on this PC, pass `--host-port`
with the tunnel's local port. The harness maps device port 8000 to that port
and restores the same mapping after its offline check. This verifies the remote
backend through the tunnel, not public HTTPS or independent mobile networks.

Both devices must start at the welcome screen. For a fresh test session, append
`--reset-test-session`. That option clears only `com.pocketdisco.internal` app
data on both devices, including its guest login and saved invite. It does not
delete server records or touch other apps. Do not use it on a session you need
to keep.

The script checks launch, create/join, presence, readiness, two-way chat,
background recovery, process restart, and saved-room retry after an offline
cold start. The last check temporarily removes the emulator's port 8000 reverse
mapping and restores it in a cleanup block. It reports only completed checks.
It does not measure playback, audio timing, Bluetooth, or real internet quality.

Results and screenshots stay in the ignored output directory. Screenshots can
contain test invite codes, so do not publish them unredacted. No access tokens
or device serial numbers are collected. The UI hierarchy is filtered to this
app and ignores clipped views before tapping.

```powershell
python -m unittest discover -s tools/mobile_e2e -v
```

## Two or three devices and a deployed backend

Use `rooms.py` for one phone and an emulator, or two phones and an emulator.
It requires two or three distinct explicit serials, with the room host first,
and a healthy production backend
already reachable through a local SSH tunnel. Install the internal APK and
unlock all devices. Leave each at the launcher or the product welcome screen,
with no saved room or personal form input. The saved app API address must be
`http://127.0.0.1:8000`. The runner inspects it without changing it.

```powershell
python tools/mobile_e2e/rooms.py --adb .local-tools/android-sdk/platform-tools/adb.exe --device HOST_PHONE_SERIAL --device emulator-5554 --host-port 18080 --output .local-tools/mobile-rooms-two-new

python tools/mobile_e2e/rooms.py --adb .local-tools/android-sdk/platform-tools/adb.exe --device HOST_PHONE_SERIAL --device FRIEND_PHONE_SERIAL --device emulator-5554 --host-port 18080 --output .local-tools/mobile-rooms-three-new
```

The default host port is 18080. Existing device port 8000 mappings must already
point there; conflicting mappings stop the run. Missing mappings are created
with `--no-rebind` and removed afterward. Matching preconfigured mappings are
preserved. The offline check temporarily removes one device's mapping and
restores it even if the UI check fails, without replacing a conflicting mapping
created by another process.

The test creates one room, joins each guest, checks presence and readiness on
every selected device, exchanges chat, backgrounds the first guest, restarts
the host process, and retries the last guest's saved room after an offline
cold start. With two clients, the same guest performs both recovery checks.
Successful runs leave the guests first and the host last through the UI. Guest accounts,
chat and the closed room remain on the backend. Failed runs may leave the test
room open for inspection; leave it manually before trying again.

No app data is cleared. An unexpected foreground app, saved room, server
address or non-test draft stops the run. UI polling is bounded to 30 seconds
per check, ADB commands to 35 seconds, health requests to 5 seconds, and new UI
checks stop after 15 minutes. A pending bounded command may finish afterward.
Results contain fixed outcomes and UTC timestamps only, without screenshots,
UI dumps, invite codes, tokens, device serials or raw exceptions. Use a new
directory under the repository's ignored `.local-tools` folder each time.

This checks the deployed room control plane through the tunnel. It does not
test public HTTPS, independent mobile networks, music playback or audio sync.

## Generated local demo playback

Use `demo.py` with one or two explicitly selected devices and the same private
production-backend tunnel used by `rooms.py`. Each device creates and leaves
its own fresh private room. This is not a shared-playback or synchronization
test. Install the internal build with the Room, Audio and Chat shortcuts.
Start at an empty welcome screen or launcher, with the saved API address set
to `http://127.0.0.1:8000`. Saved rooms and personal drafts stop the test without
being cleared.

```powershell
python tools/mobile_e2e/demo.py --adb .local-tools/android-sdk/platform-tools/adb.exe --device PHONE_SERIAL --device emulator-5554 --host-port 18080 --output .local-tools/mobile-demo-new
```

Omit the second `--device` to test only the phone. The runner plays only the
bundled generated 24-second clip. It checks no autoplay on room entry,
advancing native-backed playhead, explicit pause, five-second seeks that stay
paused, natural end, replay, background pause, process recovery without
autoplay, and a clean welcome screen after leaving during playback.

Android UI dumps can wait for a playing progress bar to stop changing. The
runner first verifies the visible controls, then plays briefly and pauses at
the same coordinates before reading the position. Cached coordinates expire
after 12 seconds, and each tap rechecks the foreground app and display bounds.
The leave check must finish within 18 seconds of starting the 24-second clip.

Where supported, the runner also checks the current Android player table for
PocketDisco's AudioTrack state before and after leaving. It needs a `started`
observation followed by two inactive observations, both completed before
22 seconds from starting playback. Every probe and intervening wait rechecks
that deadline so natural completion cannot count as stopping on exit. Late
observations, unknown formats or missing telemetry produce `not_verified`,
never a passing native-stop claim. This
parser follows the Android source for
[PlaybackActivityMonitor](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/audio/PlaybackActivityMonitor.java)
and [AudioPlaybackConfiguration](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/media/java/android/media/AudioPlaybackConfiguration.java).
Raw system dumps, app UIDs, device serials, invites and screenshots are not
written to the report. This does not record sound or measure acoustic timing.

The runner preserves matching ADB mappings and removes only mappings it
created. It temporarily lowers media volume if needed, restoring the previous
level only if nobody changed it during testing. On failure after creating a
test room, it stops only `com.pocketdisco.internal` before restoring volume.
The failed test room may stay saved for inspection; leave it manually before
rerunning. Foreground, saved-session, bounded command and 15-minute run guards
match the room smoke test. Results go into a new ignored `.local-tools` folder.

## Internal nearby handoff

Install the internal product and Test Source on a USB phone, and Capture Lab
on the emulator. Use a fresh isolated local-test API on PC port 8000, start at
the product welcome screen and leave the emulator at its launcher or the lab.
The product must use `http://127.0.0.1:8000`. Existing guest data is not cleared.

```powershell
python tools/mobile_e2e/nearby.py --adb .local-tools/android-sdk/platform-tools/adb.exe --phone PHONE_SERIAL --emulator emulator-5554 --output .local-tools/product-nearby-new
```

To test against the existing deployed API without changing the app's saved
server or credentials, explicitly select the healthy private SSH tunnel:

```powershell
python tools/mobile_e2e/nearby.py --adb .local-tools/android-sdk/platform-tools/adb.exe --phone PHONE_SERIAL --emulator emulator-5554 --deployed-backend --host-port 18080 --output .local-tools/product-nearby-deployed-new
```

This opt-in requires production backend health, an empty product welcome
screen, no saved room or personal draft, and the saved device API address
`http://127.0.0.1:8000`. It uses `Nearby Host` and `PocketDisco Room Test` as
synthetic test input. Matching port 8000 mappings are preserved; conflicting
mappings stop the run. Missing mappings use `--no-rebind` and are removed
afterward. Preflight finishes before volume changes or diagnostic app cleanup.
The default remains the isolated local-test API on host port 8000. Neither mode
tests public HTTPS. Reports label the backend mode and private API transport,
without storing raw failure messages.

The runner checks the selected backend and visible saved API address before
creating a test room. It opens the native nearby screen, authenticates the
lab receiver over ADB loopback TCP, drives explicit Android capture consent,
plays the generated tone, checks matching PCM and advancing scheduled output,
then checks that Back returns to the room, stops capture and disconnects the
receiver. It preserves the playing observation separately from the stopped
one. It does not test Bluetooth, catalog playback or acoustic skew.

The test owns its temporary port mappings and lowers volume while running.
It stops only the product and diagnostic apps before restoring volume. A
conflicting port, unexpected foreground app, different server address or
non-test form input stops the run. It leaves the created test room saved;
leave that room through the UI before repeating. Results stay inside the
repository without credentials, invite codes, device serials or raw audio.
