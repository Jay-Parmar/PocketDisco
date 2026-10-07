import copy
import io
import json
import struct
import unittest
from pathlib import Path
from unittest.mock import patch

import build_catalog as media


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.sources = media.load_sources()

    def test_three_sources_have_distinct_ids_and_explicit_cc0(self):
        self.assertEqual(len(self.sources["tracks"]), 3)
        self.assertEqual(len({track["id"] for track in self.sources["tracks"]}), 3)
        self.assertTrue(all(track["license"] == "CC0-1.0" for track in self.sources["tracks"]))

    def test_unreviewed_license_is_rejected(self):
        self.sources["tracks"][0]["license"] = "unknown"
        with patch.object(Path, "read_text", return_value=json.dumps(self.sources)):
            with self.assertRaisesRegex(ValueError, "Unreviewed license"):
                media.load_sources()

    def test_duplicate_ids_and_output_names_are_rejected(self):
        for field in ["id", "file"]:
            sources = copy.deepcopy(self.sources)
            sources["tracks"][1][field] = sources["tracks"][0][field]
            with patch.object(Path, "read_text", return_value=json.dumps(sources)):
                with self.assertRaisesRegex(ValueError, "unique"):
                    media.load_sources()

    def test_source_hash_must_be_a_lowercase_sha256(self):
        for digest in ["abc", "a" * 63, "g" * 64, "A" * 64]:
            self.sources["tracks"][0]["source_sha256"] = digest
            with patch.object(Path, "read_text", return_value=json.dumps(self.sources)):
                with self.assertRaisesRegex(ValueError, "source hash"):
                    media.load_sources()

    def test_download_links_require_https_without_credentials(self):
        for url in ["http://example.com/file.mp3", "https://user:pass@example.com/file.mp3"]:
            self.sources["tracks"][0]["source_url"] = url
            with patch.object(Path, "read_text", return_value=json.dumps(self.sources)):
                with self.assertRaisesRegex(ValueError, "source_url"):
                    media.load_sources()

    def test_filenames_cannot_escape_the_media_directory(self):
        for filename in ["../song.m4a", "..\\song.m4a", "C:\\song.m4a", "/song.m4a", "song.m4a:stream", "song.exe"]:
            with self.assertRaisesRegex(ValueError, "Unsafe media filename"):
                media.named_file(Path("assets/test-audio"), filename)

    def test_mp4_box_parser_handles_regular_extended_and_last_boxes(self):
        data = struct.pack(">I4s", 8, b"ftyp")
        data += struct.pack(">I4sQ", 1, b"moov", 16)
        data += struct.pack(">I4s", 0, b"mdat") + b"audio"
        self.assertEqual(media.mp4_boxes(io.BytesIO(data), len(data)), ["ftyp", "moov", "mdat"])

    def test_mp4_box_parser_rejects_truncation_and_bad_lengths(self):
        for data in [b"short", struct.pack(">I4s", 20, b"ftyp"), struct.pack(">I4s", 4, b"moov"), struct.pack(">I4s", 1, b"mdat")]:
            with self.assertRaises(ValueError):
                media.mp4_boxes(io.BytesIO(data), len(data))

    def test_conversion_has_pinned_codec_bitexact_and_no_overwrite_flags(self):
        args = media.encode_args(Path("ffmpeg.exe"), Path("source.flac"), Path("output.m4a"))
        self.assertEqual(args[args.index("-c:a") + 1], "aac")
        self.assertEqual(args[args.index("-profile:a") + 1], "aac_low")
        self.assertEqual(args[args.index("-ar") + 1], "48000")
        self.assertEqual(args[args.index("-cpuflags") + 1], "0")
        self.assertEqual(args.count("-bitexact"), 2)
        self.assertIn("-n", args)
        self.assertNotIn("-y", args)
        self.assertEqual(args[args.index("-movflags") + 1], "+faststart")

    def test_manifest_verification_rejects_provenance_changes(self):
        catalog = {**self.sources, "encoding": media.ENCODING}
        catalog["tracks"] = copy.deepcopy(self.sources["tracks"])
        catalog["tracks"][0]["artist"] = "different author"
        with self.assertRaisesRegex(ValueError, "provenance"):
            media.verify_files(catalog, Path("unused"), self.sources)

    def test_existing_outputs_are_not_replaced(self):
        with patch.object(media, "tools_at", return_value=(Path("ffmpeg"), Path("ffprobe"))):
            with patch.object(Path, "exists", return_value=True):
                with self.assertRaisesRegex(ValueError, "fresh output directory"):
                    media.build(self.sources, Path("sources"), Path("output"), Path("tools"))

    def test_source_mismatch_stops_before_encoding(self):
        with patch.object(media, "tools_at", return_value=(Path("ffmpeg"), Path("ffprobe"))):
            with patch.object(media, "sha256", return_value="0" * 64):
                with patch.object(media, "run") as run:
                    with self.assertRaisesRegex(ValueError, "Source hash mismatch"):
                        media.build(self.sources, Path("sources"), Path("missing-output"), Path("tools"))
                    run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
