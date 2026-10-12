# Shared nearby test engine

The lab app and PocketDisco's internal Android build use this library. It compiles
the existing lab Kotlin, resources and unit tests through Gradle source sets.
There is one implementation, not a copied player or relay.

The library needs Android 10 (API 29). PocketDisco still supports API 24 for its
room and local preview features. Only its debug and internal manifests override
this library's minimum SDK, with both the UI and native entry gated at API 29.
Do not load or launch the nearby Activity on older Android versions.

The product's release variant has no dependency on this library. Its native
bridge is a disabled stub, with no capture imports, Activity, foreground service,
Bluetooth permissions or capture permissions from this experiment. The Activity
is non-exported when embedded in PocketDisco. The standalone lab overrides that
attribute only for its own launcher.

The nearby entry is independent of the internet room. Its separate 128-bit code
and paired Bluetooth devices govern admission; the backend does not authorize
nearby peers. The room reconnects when its React Native screen returns. Back,
returning to the room, leaving the room and destroying the native bridge stop
the nearby engine. There is no automatic capture consent or source-policy bypass.

The generated source fixture remains a separate app. Install its APK as described
in the [lab instructions](../README.md) for capture tests. The product's bundled
local preview is unchanged.

From the repository root:

```powershell
apps/mobile/android/gradlew.bat -p experiments/android-capture-relay :protocol:test :core:testDebugUnitTest :source:testDebugUnitTest :core:lintDebug :lab:lintDebug :source:lintDebug :lab:assembleDebug :source:assembleDebug
```

The native test files remain under `lab/src/test`, but their task and results now
belong to `core`. Neither a successful build nor this internal entry establishes
store readiness or measured acoustic synchronization.
