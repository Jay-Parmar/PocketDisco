"""Build and verify the controlled test-audio catalog."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import subprocess
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import BinaryIO
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[2]
SOURCES = Path(__file__).with_name("sources.json")
HASH_PATTERN = re.compile(r"[a-f0-9]{64}")
FILE_PATTERN = re.compile(r"[a-z0-9][a-z0-9.-]*\.(?:m4a|mp3|ogg|flac)")
ENCODING = {
    "codec": "aac",
    "profile": "LC",
    "mime_type": "audio/mp4",
    "sample_rate_hz": 48000,
    "channels": 2,
    "target_bitrate_bps": 160000,
    "faststart": True,
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def named_file(directory: Path, filename: str) -> Path:
    if not FILE_PATTERN.fullmatch(filename):
        raise ValueError(f"Unsafe media filename: {filename}")
    path = directory / filename
    if path.resolve().parent != directory.resolve():
        raise ValueError(f"Media file leaves its directory: {filename}")
    return path


def load_sources(path: Path = SOURCES) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data["schema_version"] != 1 or data["status"] != "controlled_audio_candidate":
        raise ValueError("Unsupported source manifest")
    if not HASH_PATTERN.fullmatch(data["toolchain"]["archive_sha256"]):
        raise ValueError("Invalid toolchain hash")
    tracks = data["tracks"]
    if not tracks or len({track["id"] for track in tracks}) != len(tracks):
        raise ValueError("Track IDs must be unique")
    if len({track["file"] for track in tracks}) != len(tracks):
        raise ValueError("Output filenames must be unique")
    for track in tracks:
        if track["license"] != "CC0-1.0":
            raise ValueError(f"Unreviewed license: {track['id']}")
        for field in ["title", "artist", "attribution"]:
            if not isinstance(track[field], str) or not track[field].strip():
                raise ValueError(f"Missing {field}: {track['id']}")
        if not HASH_PATTERN.fullmatch(track["source_sha256"]):
            raise ValueError(f"Invalid source hash: {track['id']}")
        for field in ["source_page", "source_url", "license_url"]:
            parsed = urlparse(track[field])
            if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
                raise ValueError(f"Invalid {field}: {track['id']}")
        for field in ["source_file", "file"]:
            if not FILE_PATTERN.fullmatch(track[field]):
                raise ValueError(f"Invalid {field}: {track['id']}")
        if not track["file"].endswith(".m4a"):
            raise ValueError("Output must use the M4A container")
    return data


def mp4_boxes(stream: BinaryIO, file_size: int) -> list[str]:
    boxes = []
    offset = 0
    while offset < file_size:
        stream.seek(offset)
        header = stream.read(8)
        if len(header) != 8:
            raise ValueError("Truncated MP4 box")
        length, kind = struct.unpack(">I4s", header)
        header_size = 8
        if length == 1:
            extended = stream.read(8)
            if len(extended) != 8:
                raise ValueError("Truncated extended MP4 box")
            length = struct.unpack(">Q", extended)[0]
            header_size = 16
        elif length == 0:
            length = file_size - offset
        if length < header_size or offset + length > file_size:
            raise ValueError("Invalid MP4 box length")
        boxes.append(kind.decode("ascii"))
        offset += length
    return boxes


def require_faststart(path: Path) -> None:
    with path.open("rb") as source:
        boxes = mp4_boxes(source, path.stat().st_size)
    if not boxes or boxes[0] != "ftyp" or boxes.count("moov") != 1 or boxes.count("mdat") != 1:
        raise ValueError(f"Invalid MP4 layout: {path.name}")
    if boxes.index("moov") > boxes.index("mdat") or "moof" in boxes:
        raise ValueError(f"MP4 index is not at the front: {path.name}")


def run(args: list[str]) -> str:
    result = subprocess.run(args, capture_output=True, text=True, check=False)
    if result.returncode:
        raise ValueError(result.stderr.strip() or "Media tool failed")
    return result.stdout


def tools_at(directory: Path, version: str) -> tuple[Path, Path]:
    ffmpeg = directory / "ffmpeg.exe"
    ffprobe = directory / "ffprobe.exe"
    for tool in [ffmpeg, ffprobe]:
        first_line = run([str(tool), "-version"]).splitlines()[0]
        if not first_line.startswith(f"{tool.stem} version {version}-essentials_build-www.gyan.dev "):
            raise ValueError(f"Use the pinned Gyan FFmpeg {version} essentials build")
    return ffmpeg, ffprobe


def encode_args(ffmpeg: Path, source: Path, destination: Path) -> list[str]:
    return [
        str(ffmpeg), "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
        "-cpuflags", "0", "-threads", "1", "-bitexact", "-i", str(source),
        "-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
        "-c:a", "aac", "-profile:a", "aac_low", "-aac_coder", "twoloop",
        "-b:a", "160k", "-ac", "2", "-ar", "48000", "-threads:a", "1",
        "-bitexact", "-movflags", "+faststart", "-f", "ipod", str(destination),
    ]


def probe(ffprobe: Path, path: Path) -> dict:
    data = json.loads(run([
        str(ffprobe), "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path),
    ]))
    if len(data["streams"]) != 1:
        raise ValueError(f"Expected one audio stream: {path.name}")
    stream = data["streams"][0]
    if (stream["codec_type"] != "audio" or stream["codec_name"] != "aac"
            or stream["profile"] != "LC" or int(stream["sample_rate"]) != 48000
            or stream["channels"] != 2 or stream["time_base"] != "1/48000"
            or Decimal(stream["start_time"]) != 0):
        raise ValueError(f"Unexpected audio encoding: {path.name}")
    samples = int(stream["duration_ts"])
    if samples <= 0:
        raise ValueError(f"Empty audio track: {path.name}")
    duration_ms = int((Decimal(samples) * 1000 / 48000).quantize(Decimal(1), rounding=ROUND_HALF_UP))
    require_faststart(path)
    return {"duration_ms": duration_ms, "duration_samples": samples,
            "size_bytes": path.stat().st_size, "sha256": sha256(path)}


def verify_files(catalog: dict, directory: Path, sources: dict) -> None:
    for key in ["schema_version", "catalog_id", "status", "license_checked_at", "toolchain"]:
        if catalog[key] != sources[key]:
            raise ValueError(f"Catalog {key} does not match the reviewed sources")
    if catalog["encoding"] != ENCODING or len(catalog["tracks"]) != len(sources["tracks"]):
        raise ValueError("Unexpected catalog encoding or track count")
    for track, original in zip(catalog["tracks"], sources["tracks"]):
        if any(track.get(key) != value for key, value in original.items()):
            raise ValueError(f"Track provenance does not match: {original['id']}")
        for key in ["size_bytes", "duration_ms", "duration_samples"]:
            if type(track[key]) is not int or track[key] <= 0:
                raise ValueError(f"Invalid {key}: {track['id']}")
        expected_ms = int((Decimal(track["duration_samples"]) * 1000 / 48000).quantize(Decimal(1), rounding=ROUND_HALF_UP))
        if track["duration_ms"] != expected_ms:
            raise ValueError(f"Duration fields disagree: {track['id']}")
        path = named_file(directory, track["file"])
        if path.stat().st_size != track["size_bytes"] or sha256(path) != track["sha256"]:
            raise ValueError(f"Audio hash or size mismatch: {track['id']}")
        require_faststart(path)


def build(sources: dict, source_dir: Path, output_dir: Path, tool_dir: Path) -> dict:
    ffmpeg, ffprobe = tools_at(tool_dir, sources["toolchain"]["version"])
    if (output_dir / "catalog.json").exists():
        raise ValueError("Use a fresh output directory; catalog.json already exists")
    inputs = []
    for track in sources["tracks"]:
        source = named_file(source_dir, track["source_file"])
        if sha256(source) != track["source_sha256"]:
            raise ValueError(f"Source hash mismatch: {track['id']}")
        destination = named_file(output_dir, track["file"])
        if destination.exists():
            raise ValueError(f"Output already exists: {track['file']}")
        inputs.append((track, source, destination))
    output_dir.mkdir(parents=True, exist_ok=True)
    converted = []
    for track, source, destination in inputs:
        run(encode_args(ffmpeg, source, destination))
        converted.append({**track, **probe(ffprobe, destination)})
        print(f"Converted {track['id']}")
    catalog = {**sources, "encoding": ENCODING, "tracks": converted}
    verify_files(catalog, output_dir, sources)
    with (output_dir / "catalog.json").open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
    return catalog


def verify(catalog_path: Path, tool_dir: Path | None) -> None:
    sources = load_sources()
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    verify_files(catalog, catalog_path.parent, sources)
    if tool_dir is not None:
        ffmpeg, ffprobe = tools_at(tool_dir, sources["toolchain"]["version"])
        for track in catalog["tracks"]:
            path = named_file(catalog_path.parent, track["file"])
            measured = probe(ffprobe, path)
            if any(track[key] != value for key, value in measured.items()):
                raise ValueError(f"Probe mismatch: {track['id']}")
            run([str(ffmpeg), "-nostdin", "-v", "error", "-xerror", "-i", str(path), "-f", "null", "-"])
            midpoint = str(Decimal(track["duration_ms"]) / 2000)
            run([str(ffmpeg), "-nostdin", "-v", "error", "-xerror", "-ss", midpoint,
                 "-i", str(path), "-t", "1", "-f", "null", "-"])
    print(f"Verified {len(catalog['tracks'])} catalog tracks")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build_command = commands.add_parser("build")
    build_command.add_argument("--sources-dir", type=Path, required=True)
    build_command.add_argument("--output-dir", type=Path, required=True)
    build_command.add_argument("--ffmpeg-dir", type=Path, required=True)
    verify_command = commands.add_parser("verify")
    verify_command.add_argument("--catalog", type=Path, default=ROOT / "assets/test-audio/catalog.json")
    verify_command.add_argument("--ffmpeg-dir", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "build":
            build(load_sources(), args.sources_dir, args.output_dir, args.ffmpeg_dir)
        else:
            verify(args.catalog, args.ffmpeg_dir)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
