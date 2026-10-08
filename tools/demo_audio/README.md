# Generated demo audio

`generated-pulse` is a test-only signal bundled at
`apps/mobile/android/app/src/main/assets/demo_pulse.m4a`. It is synthesized from
fixed sine waves and envelopes, with no external recording, sample, or borrowed
melody. It is not a launch music catalog or evidence of device synchronization.

The 24-second clip begins with 250 ms of silence. Soft 220/330/440 Hz pulses
repeat every half second. Short 880 Hz markers repeat every second, with a
1320 Hz accent every fourth marker. Each pulse has a smooth envelope; the clip
ends with a 40 ms fade. AAC encoding can slightly smear the source boundaries.
Start with low device volume. No device or acoustic test is claimed here.

## Generate and test

Run from the repository root with Python 3.10 or later and FFmpeg:

```powershell
python -m unittest discover -s tools/demo_audio -p 'test_*.py' -v
python tools/demo_audio/generate.py --ffmpeg .local-tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe --output tools/demo_audio/reproduced.m4a
Get-FileHash -Algorithm SHA256 tools/demo_audio/reproduced.m4a
```

Omit `--output` to create the Android asset. Existing output is never replaced,
including a file created while encoding. A temporary sibling file is published
with an exclusive hard link, so the destination filesystem must support hard
links, such as NTFS or ext4. Failed encoding leaves no output file. There is no
overwrite flag.

The PCM is deterministic. The MP4 is byte-identical in two runs using Python
3.10.11 and FFmpeg `9.0.2-essentials_build-www.gyan.dev`. A different FFmpeg
version or build may produce a different AAC bitstream.

## Verified asset

- Item ID: `generated-pulse`
- Container: MP4/M4A with fast-start metadata
- Codec: AAC-LC, mono, 48,000 Hz
- Duration: 24.000000 seconds, 1,152,000 decoded samples
- Size: 220,332 bytes
- Encoded SHA-256: `a96b5a85a1f4f68b65a10ce11ead3a0f5266098a437ecddc3e21ebdff3288c7e`
- Source PCM SHA-256: `1d72bcc9522db473eb1e8a0bd16bfff77b8e6fa3e2e30d61f12d2a62e9bf8161`
- Decoded peak: -17.3 dBFS; RMS: -29.7 dBFS

FFprobe confirmed the format and duration. Full decoding and decoding after
an input seek to 12 seconds both completed with exit code 0. The midpoint check
proves decoder seeking only, not Android scheduling or acoustic accuracy.

```powershell
$demoFfmpeg = '.local-tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe'
$demoFfprobe = '.local-tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffprobe.exe'
$demoClip = 'apps/mobile/android/app/src/main/assets/demo_pulse.m4a'
& $demoFfprobe -v error -show_entries format=format_name,duration,size:stream=codec_name,profile,sample_rate,channels,duration -of json $demoClip
& $demoFfmpeg -v error -xerror -i $demoClip -map 0:a:0 -f null -
& $demoFfmpeg -v error -xerror -ss 12 -i $demoClip -t 2 -map 0:a:0 -f null -
& $demoFfmpeg -hide_banner -nostats -i $demoClip -af volumedetect -f null -
```
