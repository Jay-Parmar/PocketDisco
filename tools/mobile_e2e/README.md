# Android room smoke test

Runs the internal app through its visible UI on one USB phone and one emulator.
Start the local API, install the internal APK, and reverse port 8000 on each
device first. Unlock the phone and leave it available during the run.

From the repository root:

```powershell
python tools/mobile_e2e/run.py --adb .local-tools/android-sdk/platform-tools/adb.exe --output .local-tools/mobile-e2e
```

Both devices must start at the welcome screen. For a fresh test session, append
`--reset-test-session`. That option clears only `com.pocketdisco.internal` app
data on both devices, including its guest login and saved invite. It does not
delete server records or touch other apps. Do not use it on a session you need
to keep.

The script checks launch, create/join, presence, readiness, two-way chat,
background recovery, and process restart. It reports only completed checks.
It does not measure playback, audio timing, Bluetooth, or real internet quality.

Results and screenshots stay in the ignored output directory. Screenshots can
contain test invite codes, so do not publish them unredacted. No access tokens
or device serial numbers are collected. The UI hierarchy is filtered to this
app and ignores clipped views before tapping.

```powershell
python -m unittest discover -s tools/mobile_e2e -v
```
