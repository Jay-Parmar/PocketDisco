# Capture relay lab

Internal Android 10+ diagnostic, also available from PocketDisco's internal
room screen through the [shared library](core/README.md). It
does not turn a stock phone into an ordinary Bluetooth speaker. Test only
generated audio or audio you have permission to transmit.

The lab captures playback from one selected app UID after Android consent.
Source capture restrictions remain in force. There is no microphone fallback,
screen recording, music-app patching, protected-stream extraction, backend
audio relay or provider integration approval.

Audio is fixed 16 kHz, mono, PCM16 little-endian in 20 ms frames. Receivers
schedule output against the source clock with a shared 1.5-second buffer.
This remains a low-fidelity diagnostic, not a music-quality release. Capture
does not delay the original speaker, so receivers lag it. Each node allows
three pending or active direct links and forwards at most three hops. There
is no automatic discovery or unrestricted mesh.

## Build

Use JDK 17, SDK package `platforms;android-37.0` and build tools 37.0.0. Point `JAVA_HOME`,
`ANDROID_HOME`, `ANDROID_USER_HOME` and `GRADLE_USER_HOME` at the repository's
local tools. The mobile wrapper supplies Gradle 9.5.0. From the repository root:

```powershell
apps/mobile/android/gradlew.bat -p experiments/android-capture-relay :protocol:test :core:testDebugUnitTest :source:testDebugUnitTest :core:lintDebug :lab:lintDebug :source:lintDebug :lab:assembleDebug :source:assembleDebug --max-workers=2 --console=plain
```

Use `bash apps/mobile/android/gradlew` instead on Linux. No React Native or
Metro setup is needed for these apps. The SDK packages can be installed with:

```text
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"
```

| App | Package | APK, relative to this directory |
|---|---|---|
| Capture Lab | `com.pocketdisco.capturelab` | `lab/build/outputs/apk/debug/lab-debug.apk` |
| Test Source | `com.pocketdisco.capturelab.source` | `source/build/outputs/apk/debug/source-debug.apk` |

These are debug diagnostics, not Play artifacts. They do not replace
PocketDisco or the installed music app. Install Capture Lab on every test
phone and Test Source on the sending phone. The [source notes](source/README.md)
describe the generated tone and bundled MP3 provenance.

Update every peer together. The source-clock protocol is version 2 and rejects
older lab peers. The [synchronization checkpoint](../../docs/13-synchronized-capture.md)
records tested APK hashes and the limits of the hardware results.

CI and local debug builds may use different signing keys. Android will not
update an installed package with an APK signed by a different key. Use APKs
from one build for a test run; do not automatically uninstall existing apps
to work around a signature mismatch.

## Three-phone paired-Bluetooth check

Use three physical phones, called A, B and C. Start with quiet volumes and
keep receiver screens open. Backgrounding a receiver stops its session.

1. Pair A with B, then B with C in Android Bluetooth settings.
2. On A, tap **New session code**. Copy its 32 hexadecimal characters
   (128 random bits) into the session-code field on B and C. Share the code
   only with the test participants.
3. On A, tap **Host paired Bluetooth**. On B, tap **Receive paired Bluetooth**
   and select A. Accept Bluetooth permission if requested.
4. On B, also tap **Host paired Bluetooth**. On C, tap **Receive paired
   Bluetooth** and select B. **Refresh metrics** should show one peer on A,
   two on B and one on C before capture starts.
5. On A, select **Generated source**, tap **Start capture** and approve
   Android's audio/screen consent. Tap **Open generated source**, then
   **Play allowed**. B and C should receive the generated tone through the
   selected chain. Allow startup calibration to settle, then check that both
   report a ready clock and `playing` output. Note uncertainty, output error,
   resyncs and drops. Keep the sender quiet when comparing receivers.
6. Try **Play blocked** on A's Test Source. The tone still plays locally, but
   capture should become silent. Silence alone is not proof of a policy
   block: a paused or unavailable source can also be silent.
7. Tap **Stop session** in each Capture Lab and **Stop** in Test Source.
   Stopping capture does not control or stop the original music app.

Do not start capture on B or C during this one-source check. Reconnect through
the receive button after a lost link. Create a fresh code after stopping all
sessions when changing participants. Keep this first check within normal
Bluetooth range; range and audible-delay measurements are separate tests.

The optional **Music app local test file** source selects the installed
`app.revanced.android.apps.youtube.music` UID. The companion's **Open generated
file in music app** button opens only the bundled generated MP3. It does not
open the provider's catalog. A successful local-file test would not establish
permission or support for capturing catalog music.

## Phone-to-emulator USB/TCP check

This substitutes USB-forwarded TCP for Bluetooth. It does not validate a
physical radio link, range, multi-hop radio performance or audible sync.

First check `adb forward --list`. If port 18990 is already used, leave that
mapping alone. Otherwise, create a mapping for the sending phone:

```powershell
adb -s PHONE_SERIAL forward --no-rebind tcp:18990 tcp:18990
```

Use the same session code on the phone and emulator. Tap **Host USB/TCP test**
on the phone and **Receive USB/TCP test** on the emulator. The phone listener
binds only `127.0.0.1:18990`; the emulator connects through the PC at
`10.0.2.2:18990`. Do not forward this port on the router or expose it publicly.
Wait for both peers before starting the generated-source capture above.

After stopping both sessions, remove only the mapping created for this test:

```powershell
adb -s PHONE_SERIAL forward --remove tcp:18990
```

For a physical USB receiver, first check its `adb reverse --list`. If port
18991 is unused, map `tcp:18991` on that receiver to the PC's `tcp:18990`,
then tap **Receive USB phone test**. Remove only that reverse mapping after
the test. The [three-device runner](../../tools/capture_relay_e2e/README.md#scheduled-receiver-check)
owns and cleans up these mappings for USB fan-out and mixed-link checks.

## Receiver timing

Authenticated clock probes travel to the original source, including across
relay hops. Receivers require fresh estimates before scheduling frames. Clock
expiry, link loss, late audio and timestamp failures stop or rebuffer output;
old audio is never drained as a delayed backlog. The bounded queue holds at
most 100 frames. AudioTrack timestamps calibrate output lead and report error
against the scheduled timeline. Startup calibration may discard frames.

All receivers use the same future source deadline; each hop does not add a new
1.5-second delay. The sender's original app is outside this timeline. A ready
clock or a small software output error is not a microphone measurement. Long
sessions, changing audio routes, three physical Bluetooth nodes and ten-start
acoustic trials remain hardware gates.

## Metrics and privacy

While a session is active, the app updates its private `files/report.json`
about once a second. Writes are atomic. **Refresh metrics** and **Stop session** also write it.
Tap **Show details** to see the full snapshot in the app.
It contains frame counts, peaks, hashes, relay state and output progress, not
raw audio or the join code. Captured PCM uses bounded memory queues and is
transmitted over the selected direct test links. It is not saved or sent to
the backend. The generated files in Test Source are not recordings of captured
audio.

The code is retained in app-private preferences until changed. Use a new code
for a new group and do not include it in screenshots, issues or test reports.
For a locally installed debug build, read the latest report with:

```powershell
adb -s DEVICE_SERIAL shell run-as com.pocketdisco.capturelab cat files/report.json
```

Compare the first-100-frame hashes only when peers joined before capture and
the same frame window arrived without drops. Late joins and reconnects can
produce different windows. AudioTrack progress demonstrates output activity,
not measured sound at the speakers. The [device runner](../../tools/capture_relay_e2e/README.md)
checks matching non-silent frame hashes and software playback activity.
CI runs JVM tests, host-only runner tests, lint and builds. It does not run
device tests; physical Bluetooth and acoustic results must be recorded separately.
