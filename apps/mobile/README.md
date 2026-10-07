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

