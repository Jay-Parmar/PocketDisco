# Test-audio tools

Run from the repository root with Python 3.10 or later. No Python dependencies
are needed.

```powershell
python -m unittest discover -s tools/media -v
python tools/media/build_catalog.py verify
```

The first check covers manifest validation, safe paths, MP4 indexing, conversion
flags, binary verification, and committed asset hashes. The second checks the
catalog's provenance, file hashes, sizes, durations, and front-loaded MP4 index.

## Pinned tools and sources

[FFmpeg's download page](https://ffmpeg.org/download.html) links the
[Gyan Windows builds](https://www.gyan.dev/ffmpeg/builds/). Use the 9.0.2
essentials archive linked in [sources.json](sources.json). Match its SHA256 to
the pinned value and the linked published checksum before extraction. Extract
inside `.local-tools/ffmpeg`; do not install it system-wide. The conversion tool
also checks both executable hashes before running them.

Download the three `source_url` files from `sources.json` into
`.local-tools/music-sources`, naming each with its `source_file` value. These
originals are not committed. The builder checks every source hash before encoding.

```powershell
$mediaTools = '.local-tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin'
python tools/media/build_catalog.py build --sources-dir .local-tools/music-sources --output-dir .local-tools/music-rebuild --ffmpeg-dir $mediaTools
python tools/media/build_catalog.py verify --catalog .local-tools/music-rebuild/catalog.json --ffmpeg-dir $mediaTools
```

Use a fresh output directory. Existing catalog and audio files are never
overwritten. Conversion uses one thread, disabled CPU-specific optimizations,
bitexact mode, fixed AAC settings, and no inherited metadata.
[FFmpeg documents fast-start MP4 indexing](https://ffmpeg.org/ffmpeg-formats.html#mov_002c-mp4_002c-ismv)
and [bitexact mode](https://ffmpeg.org/ffmpeg.html).

With `--ffmpeg-dir`, verification also probes the actual codec and duration,
decodes the full files, and tests a midpoint seek. Compare each rebuilt SHA256
against `assets/test-audio/catalog.json`. Reproducibility is checked for this
pinned build; other FFmpeg builds may produce different bytes.

Linux CI uses its installed FFmpeg only to verify committed files:

```sh
python3 tools/media/build_catalog.py verify --system-ffmpeg
```

This checks committed hashes before probing and decoding with `ffmpeg` and
`ffprobe` from `PATH`, and reports their versions. It does not encode audio or
change the catalog. The `build` command always requires the pinned Windows
binaries; `--system-ffmpeg` is a verification-only option.

FFmpeg remains local tooling and is not bundled with the app. This script does
not download, host, proxy, or play any audio.
