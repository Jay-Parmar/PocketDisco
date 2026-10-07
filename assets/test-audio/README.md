# Controlled test audio

Three complete tracks for the next playback experiment. Their creators' pages
listed CC0 1.0 when checked on 2026-10-07. These are controlled-audio candidates,
not an approved launch catalog or evidence that synchronization gates have passed.

| Track | Creator | Duration | File |
|---|---|---:|---|
| [Electric](https://opengameart.org/content/electric-0) | Sudocolon | 201.273 s | `electric.m4a` |
| [Technological Messup](https://opengameart.org/content/technological-messup) | Centurion_of_war | 106.667 s | `technological-messup.m4a` |
| [Technomania101](https://opengameart.org/content/technomania101-2000s-europop-electronic-dance-music) | Fupi | 130.307 s | `technomania101.m4a` |

[catalog.json](catalog.json) records the original download URLs, source and output
SHA256 hashes, credits, license links, presentation durations, sample counts, and
encoding settings. Keep those credits visible when playback is integrated. No
artist endorsement is implied.

## License boundary

[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) permits copying,
adaptation, distribution, and commercial use of the affirmer's covered rights.
It does not establish ownership of third-party material or clear other rights.
The [legal code](https://creativecommons.org/publicdomain/zero/1.0/legalcode.en)
contains the waiver, fallback license, and disclaimers. This record confirms the
uploaders' published declarations, not an independent chain-of-title audit.

The files were converted from the creators' MP3, Ogg, and FLAC downloads to
AAC-LC in an MP4/M4A container. They use stereo 48 kHz audio, a 160 kbit/s target,
and an index at the start. No excerpting, mixing, speed changes, normalization,
or added audio was applied. MP3 and Ogg sources undergo another lossy encode.

## Verification

On 2026-10-07, all three output hashes and durations passed catalog verification.
FFmpeg decoded each entire file and a one-second segment after a midpoint seek
without errors. A second independent conversion using the pinned Windows build
produced identical audio hashes and an identical catalog. This is a file-level
check, not a real-device playback or acoustic synchronization result.

The FastAPI service does not serve these files. A later provider adapter may
bundle them or use a separately configured media origin; each device must play
its own copy. See [rebuild instructions](../../tools/media/README.md).
