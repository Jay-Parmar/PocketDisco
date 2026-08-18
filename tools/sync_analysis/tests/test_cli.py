from __future__ import annotations

import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from tools.sync_analysis.cli import main


def record(trial: int, device: str, timestamp_ms: float) -> dict[str, object]:
    return {
        "schema_version": 1,
        "event_type": "acoustic_onset",
        "trial_id": f"trial-{trial:02d}",
        "start_id": "start-01",
        "device_id": device,
        "provider": "licensed_audio",
        "output_category": "built_in",
        "outcome": "ok",
        "timestamp_ms": timestamp_ms,
        "clock_id": "capture-1",
    }


class CliTests(unittest.TestCase):
    def test_json_report_and_success_exit_code(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            phone_a_path = Path(directory, "phone-a.jsonl")
            phone_b_path = Path(directory, "phone-b.jsonl")
            phone_a_lines = []
            phone_b_lines = []
            for trial in range(1, 11):
                phone_a_lines.append(json.dumps(record(trial, "phone-a", trial * 1000)))
                phone_b_lines.append(
                    json.dumps(record(trial, "phone-b", trial * 1000 + 25))
                )
            phone_a_path.write_text("\n".join(phone_a_lines), encoding="utf-8")
            phone_b_path.write_text("\n".join(phone_b_lines), encoding="utf-8")
            stdout = io.StringIO()

            with redirect_stdout(stdout):
                exit_code = main(
                    [str(phone_a_path), str(phone_b_path), "--format", "json"]
                )

        report = json.loads(stdout.getvalue())
        self.assertEqual(exit_code, 0)
        self.assertTrue(report["gate"]["passed"])
        self.assertEqual(len(report["categories"][0]["starts"]), 10)

    def test_failed_gate_uses_exit_code_one(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "telemetry.jsonl")
            path.write_text(
                "\n".join(
                    [
                        json.dumps(record(1, "phone-a", 1000)),
                        json.dumps(record(1, "phone-b", 1010)),
                    ]
                ),
                encoding="utf-8",
            )
            stdout = io.StringIO()

            with redirect_stdout(stdout):
                exit_code = main([str(path), "--format", "json"])

        self.assertEqual(exit_code, 1)

    def test_invalid_input_returns_structured_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "bad.jsonl")
            path.write_text('{"schema_version": 1}\nnot-json\n', encoding="utf-8")
            stderr = io.StringIO()

            with redirect_stderr(stderr):
                exit_code = main([str(path), "--format", "json"])

        error = json.loads(stderr.getvalue())
        self.assertEqual(exit_code, 2)
        self.assertEqual(error["error"], "input_validation_failed")
        self.assertGreater(len(error["issues"]), 1)


if __name__ == "__main__":
    unittest.main()
