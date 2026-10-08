import argparse
import array
import math
import os
import subprocess
import sys
import tempfile
from pathlib import Path

SAMPLE_RATE = 48000
DURATION_SECONDS = 24
ITEM_ID = "generated-pulse"

REPOSITORY = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = REPOSITORY / "apps/mobile/android/app/src/main/assets/demo_pulse.m4a"


def generate_pcm() -> bytes:
    samples = array.array("h")
    frames = SAMPLE_RATE * DURATION_SECONDS
    lead_in = SAMPLE_RATE // 4
    pulse_frames = SAMPLE_RATE // 2
    pulse_length = round(0.28 * SAMPLE_RATE)
    marker_length = round(0.08 * SAMPLE_RATE)
    end_fade = round(0.04 * SAMPLE_RATE)

    for frame in range(frames):
        active = frame - lead_in
        value = 0.0
        if active >= 0:
            phase_frame = active % pulse_frames
            phase = phase_frame / SAMPLE_RATE
            if phase_frame < pulse_length:
                envelope = math.sin(math.pi * phase_frame / pulse_length) ** 2
                value = envelope * (
                    0.085 * math.sin(math.tau * 220 * phase)
                    + 0.045 * math.sin(math.tau * 330 * phase)
                    + 0.020 * math.sin(math.tau * 440 * phase)
                )
            marker_frame = active % SAMPLE_RATE
            if marker_frame < marker_length:
                frequency = 1320 if active // SAMPLE_RATE % 4 == 0 else 880
                envelope = math.sin(math.pi * marker_frame / marker_length) ** 2
                value += (
                    0.065 * envelope * math.sin(math.tau * frequency * marker_frame / SAMPLE_RATE)
                )
            value *= min(1.0, (frames - 1 - frame) / end_fade)
        samples.append(round(value * 32767))

    if sys.byteorder != "little":
        samples.byteswap()
    return samples.tobytes()


def render(output: Path, ffmpeg: Path) -> None:
    if output.exists() or output.is_symlink():
        raise FileExistsError(f"Output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".demo-audio-", dir=output.parent) as scratch:
        encoded = Path(scratch) / "demo_pulse.m4a"
        subprocess.run(
            [
                str(ffmpeg),
                "-hide_banner",
                "-loglevel",
                "error",
                "-nostdin",
                "-n",
                "-f",
                "s16le",
                "-ar",
                str(SAMPLE_RATE),
                "-ac",
                "1",
                "-i",
                "pipe:0",
                "-c:a",
                "aac",
                "-b:a",
                "96k",
                "-flags:a",
                "+bitexact",
                "-fflags",
                "+bitexact",
                "-map_metadata",
                "-1",
                "-movflags",
                "+faststart",
                str(encoded),
            ],
            input=generate_pcm(),
            capture_output=True,
            check=True,
            timeout=60,
        )
        os.link(encoded, output)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Generate PocketDisco's local test pulse.")
    parser.add_argument("--ffmpeg", type=Path, default=Path("ffmpeg"))
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)
    try:
        render(args.output, args.ffmpeg)
    except (OSError, subprocess.SubprocessError) as exc:
        parser.error(f"Audio generation failed: {exc}")
    print(f"Created {ITEM_ID}: {args.output}")


if __name__ == "__main__":
    main()
