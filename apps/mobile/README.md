# PocketDisco Android

React Native 0.87.1, TypeScript, Android 7 or newer. Target SDK 36.

```powershell
npm ci
npm test -- --runInBand
npm run typecheck
npm run lint
cd android
./gradlew assembleInternal
```

The internal build bundles JavaScript and uses a debug signing key. It is only
for local testing. The release build does not use a debug key or permit HTTP.
Publishable signing and the production HTTPS endpoint are configured separately.
Do not commit signing keys or credentials.

## Phone and emulator

Start the API using [its local-test instructions](../../services/api/README.md).
Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the Android SDK. This workspace
keeps tools and caches under `.local-tools/` at the repository root. Use Node
24.21.0. The build compiles against SDK 37 and targets SDK 36.

The internal package is `com.pocketdisco.internal`. Install
`android/app/build/outputs/apk/internal/app-internal.apk` with `adb install -r`.
For each selected device, run `adb reverse tcp:8000 tcp:8000`. The app defaults
to `http://127.0.0.1:8000`; no Metro server is needed for this variant.

[The smoke loop](../../tools/mobile_e2e/README.md) drives one USB phone and one
emulator. Local SQLite tests are separate from the real PostgreSQL/Redis CI job.
The current app has private rooms and chat, not music playback.

## Security and release boundary

Guest credentials and the saved invite are encrypted with an Android Keystore
key. Writes are serialized and checked before leaving or switching servers.
Session preferences are excluded from cloud backup and device transfer. This
does not provide account recovery after uninstall or device loss.

The release endpoint remains `https://api.invalid`, signing is unconfigured,
and privacy/deletion/retention/moderation work remains open. An unsigned release
build only checks compilation and shrinking, not release readiness.

On 2026-10-07, `npm audit` reports 53 affected dependency entries across two
upstream advisories: [braces](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)
and [sprintf-js](https://github.com/advisories/GHSA-hp3w-g68c-fv3c). Their latest
stable versions are still affected. Do not force-downgrade React Native to
satisfy the audit. Review exposure and available fixes before release, and do
not expose Metro or other development tooling to untrusted networks.

Remaining lint warnings include React Native compatibility settings, template
resource references, and intentional checked preference commits on a worker
thread. Internal and release lint currently have no errors.

The [delivery checkpoint](../../docs/10-mobile-checkpoint.md) records a separate
native RELRO alignment finding. A successful build and ZIP alignment check do
not establish 16 KiB-device compatibility.
