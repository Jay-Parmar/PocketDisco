import array
import hashlib
import math
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from generate import DURATION_SECONDS, SAMPLE_RATE, generate_pcm, main, render


class SignalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pcm = generate_pcm()
        cls.samples = array.array("h", cls.pcm)
        if sys.byteorder != "little":
            cls.samples.byteswap()

    def test_exact_duration(self):
        self.assertEqual(SAMPLE_RATE, 48000)
        self.assertEqual(DURATION_SECONDS, 24)
        self.assertEqual(len(self.samples), SAMPLE_RATE * DURATION_SECONDS)

    def test_conservative_peak_and_rms(self):
        peak = max(abs(sample) for sample in self.samples) / 32768
        rms = math.sqrt(sum(sample * sample for sample in self.samples) / len(self.samples)) / 32768
        self.assertGreater(peak, 0.08)
        self.assertLess(peak, 0.24)
        self.assertGreater(rms, 0.01)
        self.assertLess(rms, 0.07)

    def test_silent_lead_in_and_faded_end(self):
        self.assertFalse(any(self.samples[: SAMPLE_RATE // 4]))
        self.assertEqual(self.samples[-1], 0)
        self.assertLess(max(abs(value) for value in self.samples[-48:]), 64)

    def test_half_second_pulses_have_silent_gaps(self):
        for second in range(23):
            active = self.samples[
                int((second + 0.3) * SAMPLE_RATE) : int((second + 0.4) * SAMPLE_RATE)
            ]
            quiet = self.samples[
                int((second + 0.6) * SAMPLE_RATE) : int((second + 0.7) * SAMPLE_RATE)
            ]
            self.assertTrue(any(active))
            self.assertFalse(any(quiet))

    def test_pcm_is_deterministic(self):
        self.assertEqual(hashlib.sha256(generate_pcm()).digest(), hashlib.sha256(self.pcm).digest())


class RenderTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="test-", dir=Path(__file__).parent)
        self.addCleanup(self.scratch.cleanup)
        self.output = Path(self.scratch.name) / "clip.m4a"

    @patch("generate.subprocess.run")
    def test_existing_output_is_untouched(self, run):
        self.output.write_bytes(b"keep this")
        with self.assertRaises(FileExistsError):
            render(self.output, Path("ffmpeg"))
        self.assertEqual(self.output.read_bytes(), b"keep this")
        run.assert_not_called()

    @patch("generate.generate_pcm", return_value=b"pcm")
    @patch("generate.subprocess.run")
    def test_encoder_failure_leaves_no_output(self, run, pcm):
        run.side_effect = subprocess.CalledProcessError(1, "ffmpeg")
        with self.assertRaises(subprocess.CalledProcessError):
            render(self.output, Path("ffmpeg"))
        self.assertEqual(list(self.output.parent.iterdir()), [])

    @patch("generate.generate_pcm", return_value=b"pcm")
    @patch("generate.subprocess.run")
    def test_success_uses_aac_and_an_exclusive_publish(self, run, pcm):
        def encode(command, **kwargs):
            self.assertIn("aac", command)
            self.assertIn("+faststart", command)
            self.assertIn("-n", command)
            self.assertEqual(kwargs["input"], b"pcm")
            self.assertEqual(kwargs["timeout"], 60)
            Path(command[-1]).write_bytes(b"encoded")

        run.side_effect = encode
        render(self.output, Path("ffmpeg"))
        self.assertEqual(self.output.read_bytes(), b"encoded")
        self.assertEqual(list(self.output.parent.iterdir()), [self.output])

    @patch("generate.generate_pcm", return_value=b"pcm")
    @patch("generate.subprocess.run")
    def test_a_concurrent_output_is_not_replaced(self, run, pcm):
        def encode(command, **kwargs):
            Path(command[-1]).write_bytes(b"encoded")
            self.output.write_bytes(b"another writer")

        run.side_effect = encode
        with self.assertRaises(FileExistsError):
            render(self.output, Path("ffmpeg"))
        self.assertEqual(self.output.read_bytes(), b"another writer")
        self.assertEqual(list(self.output.parent.iterdir()), [self.output])

    @patch("generate.subprocess.run")
    @patch("sys.stderr")
    def test_cli_refuses_to_overwrite(self, stderr, run):
        self.output.write_bytes(b"keep this")
        with self.assertRaises(SystemExit) as raised:
            main(["--ffmpeg", "ffmpeg", "--output", str(self.output)])
        self.assertEqual(raised.exception.code, 2)
        self.assertEqual(self.output.read_bytes(), b"keep this")
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
