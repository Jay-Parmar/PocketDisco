# Generated audio source

The allowed and blocked buttons play the same quiet, generated PCM tone with
different Android capture policies. No third-party audio is included.

The local-file button opens `generated-pulse.mp3` in the installed music app.
The file is a conversion of PocketDisco's mathematically generated demo pulse,
not audio downloaded from a music service. See [the generator](../../../tools/demo_audio/README.md).

## MP3 provenance

- Source: `apps/mobile/android/app/src/main/assets/demo_pulse.m4a`
- Source SHA256: `a96b5a85a1f4f68b65a10ce11ead3a0f5266098a437ecddc3e21ebdff3288c7e`
- Asset: `experiments/android-capture-relay/source/src/main/assets/generated-pulse.mp3`
- Asset SHA256: `ad5d10d701a32df76d91adbc0fbc7b8e33d5ab8cecf01cdf6109ffedb97ce59c`
- Format: MP3, 24 seconds, mono, 16 kHz, 48 kbit/s, 144764 bytes
- Tool: FFmpeg 9.0.2 essentials build, `libmp3lame`

Conversion command, run from the repository root:

```powershell
& .local-tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe -hide_banner -n -i apps/mobile/android/app/src/main/assets/demo_pulse.m4a -map 0:a:0 -vn -map_metadata -1 -ac 1 -ar 16000 -c:a libmp3lame -b:a 48k experiments/android-capture-relay/source/src/main/assets/generated-pulse.mp3
```

The provider grants read-only access to two exact URIs. Callers cannot modify
the samples, access arbitrary files, or open music-app catalog content through it.

```text
content://com.pocketdisco.capturelab.source.sample/generated-pulse.mp3
content://com.pocketdisco.capturelab.source.sample/generated-tone.wav
```

The WAV is generated locally by `GeneratedTone`; the MP3 is bundled with this
test app. Neither file contains captured audio. Capturing a permitted local
sample does not establish permission to capture a provider's music catalog.
