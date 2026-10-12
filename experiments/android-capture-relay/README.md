# Capture relay lab

Internal Android diagnostic, separate from the PocketDisco product app. Nothing here enables a stock phone to become an ordinary Bluetooth speaker.

This experiment tests user-approved playback capture and bounded PCM relaying. Test only generated audio or audio you have permission to transmit. Source apps can block capture. No microphone fallback, protected-stream extraction, music-app patching, server audio relay, or provider approval is included.

The first test uses a USB-forwarded TCP connection from a physical phone to the emulator. Paired Bluetooth sockets are a separate hardware test. Emulator results do not establish physical Bluetooth connectivity, range, multi-hop performance, or audible synchronization. Capture does not delay the original source speaker, so receivers will lag it.

## Build

Use the repository's JDK 17, Android SDK and Gradle cache. From the repository root:

```powershell
apps/mobile/android/gradlew.bat -p experiments/android-capture-relay :protocol:test :lab:assembleDebug :source:assembleDebug
```

Packages are `com.pocketdisco.capturelab` and `com.pocketdisco.capturelab.source`. Do not upload these debug diagnostics to Play. They do not replace PocketDisco or the installed music app.
