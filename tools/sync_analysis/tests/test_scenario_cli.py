from __future__ import annotations

import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from tools.sync_analysis.cli import main


CLIENTS = ("phone", "emulator", "windows")


def record(
    measurement: str,
    device_id: str,
    timestamp_ms: float,
    *,
    scenario_id: str = "mixed-01",
    output_id: str | None = None,
) -> dict[str, object]:
    acoustic = measurement == "acoustic_onset"
    result: dict[str, object] = {
        "schema_version": 2,
        "event_type": measurement,
        "scenario_id": scenario_id,
        "trial_id": "trial-01",
        "start_id": "start-01",
        "device_id": device_id,
        "platform": "windows" if device_id == "windows" else "android",
        "environment": "emulator" if device_id == "emulator" else "physical",
        "provider": "generated_audio",
        "output_category": "virtual" if device_id == "emulator" else "built_in",
        "route_mode": "app_fanout" if device_id == "windows" else "single",
        "outcome": "ok",
        "timestamp_ms": timestamp_ms,
        "clock_id": "capture:mixed-01" if acoustic else "coordinator:trial-01",
        "clock_source": "external_capture" if acoustic else "coordinator_estimate",
        "clock_uncertainty_ms": 2,
    }
    if acoustic:
        result["output_id"] = output_id
    else:
        result["target_timestamp_ms"] = 10_000
    return result


def client_records(scenario_id: str = "mixed-01") -> list[dict[str, object]]:
    result = []
    for measurement, base in (
        ("command_issued", 10_002),
        ("playback_observed", 10_020),
    ):
        for index, device_id in enumerate(CLIENTS):
            result.append(
                record(
                    measurement,
                    device_id,
                    base + index,
                    scenario_id=scenario_id,
                )
            )
    return result


def scenario_args(path: Path, scenario_id: str = "mixed-01") -> list[str]:
    arguments = [
        str(path),
        "--mode",
        "scenario",
        "--scenario-id",
        scenario_id,
        "--format",
        "json",
        "--minimum-starts",
        "1",
    ]
    for client in CLIENTS:
        arguments.extend(["--expected-client", client])
    return arguments


class ScenarioCliTests(unittest.TestCase):
    def test_minimum_starts_makes_short_scenario_incomplete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "mixed.jsonl")
            path.write_text(
                "\n".join(json.dumps(item) for item in client_records()),
                encoding="utf-8",
            )
            stdout = io.StringIO()
            arguments = scenario_args(path)
            minimum_index = arguments.index("--minimum-starts") + 1
            arguments[minimum_index] = "2"

            with redirect_stdout(stdout):
                exit_code = main(arguments)

        report = json.loads(stdout.getvalue())
        self.assertEqual(exit_code, 1)
        self.assertFalse(report["summary"]["minimum_starts_met"])
        self.assertEqual(report["configuration"]["minimum_valid_starts"], 2)

    def test_json_report_succeeds_without_claiming_acoustic_measurement(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "mixed.jsonl")
            path.write_text(
                "\n".join(json.dumps(item) for item in client_records()),
                encoding="utf-8",
            )
            stdout = io.StringIO()

            with redirect_stdout(stdout):
                exit_code = main(scenario_args(path))

        report = json.loads(stdout.getvalue())
        self.assertEqual(exit_code, 0)
        self.assertTrue(report["summary"]["command_player_complete"])
        self.assertEqual(report["summary"]["acoustic_status"], "not_measured")
        self.assertNotIn("gate", report)

    def test_requested_acoustic_output_makes_missing_capture_incomplete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "mixed.jsonl")
            path.write_text(
                "\n".join(json.dumps(item) for item in client_records()),
                encoding="utf-8",
            )
            stdout = io.StringIO()
            arguments = scenario_args(path)
            arguments.extend(
                ["--expected-acoustic-output", "phone/system"]
            )
            arguments.extend(
                ["--expected-acoustic-output", "windows/output-1"]
            )

            with redirect_stdout(stdout):
                exit_code = main(arguments)

        report = json.loads(stdout.getvalue())
        self.assertEqual(exit_code, 1)
        self.assertEqual(report["summary"]["acoustic_status"], "not_measured")

    def test_selects_one_scenario_from_a_v2_file(self) -> None:
        records = client_records("mixed-01") + client_records("mixed-02")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "mixed.jsonl")
            path.write_text(
                "\n".join(json.dumps(item) for item in records),
                encoding="utf-8",
            )
            stdout = io.StringIO()

            with redirect_stdout(stdout):
                exit_code = main(scenario_args(path, "mixed-02"))

        report = json.loads(stdout.getvalue())
        self.assertEqual(exit_code, 0)
        self.assertEqual(report["scenario_id"], "mixed-02")
        self.assertEqual(report["summary"]["records"], 6)

    def test_expected_client_values_are_trimmed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "mixed.jsonl")
            path.write_text(
                "\n".join(json.dumps(item) for item in client_records()),
                encoding="utf-8",
            )
            stdout = io.StringIO()
            arguments = scenario_args(path)
            phone_index = arguments.index("phone")
            arguments[phone_index] = "  phone  "

            with redirect_stdout(stdout):
                exit_code = main(arguments)

        self.assertEqual(exit_code, 0)

    def test_acoustic_output_rejects_extra_separator(self) -> None:
        stderr = io.StringIO()

        with redirect_stderr(stderr), self.assertRaises(SystemExit) as context:
            main(
                [
                    "input.jsonl",
                    "--expected-acoustic-output",
                    "windows/output/1",
                ]
            )

        self.assertEqual(context.exception.code, 2)
        self.assertIn("must use DEVICE_ID/OUTPUT_ID", stderr.getvalue())

    def test_markdown_names_each_timing_level(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "mixed.jsonl")
            path.write_text(
                "\n".join(json.dumps(item) for item in client_records()),
                encoding="utf-8",
            )
            stdout = io.StringIO()
            arguments = scenario_args(path)
            format_index = arguments.index("json")
            arguments[format_index] = "markdown"

            with redirect_stdout(stdout):
                exit_code = main(arguments)

        markdown = stdout.getvalue()
        self.assertEqual(exit_code, 0)
        self.assertIn("command_issued", markdown)
        self.assertIn("playback_observed", markdown)
        self.assertIn("acoustic_onset", markdown)
        self.assertIn("does not evaluate the Phase 0 acoustic gate", markdown)
        self.assertNotIn("**PASS**", markdown)

    def test_phase0_mode_rejects_v2_records(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "mixed.jsonl")
            path.write_text(json.dumps(client_records()[0]), encoding="utf-8")
            stderr = io.StringIO()

            with redirect_stderr(stderr):
                exit_code = main([str(path), "--format", "json"])

        error = json.loads(stderr.getvalue())
        self.assertEqual(exit_code, 2)
        self.assertEqual(error["issues"][0]["code"], "unsupported_analysis_mode")


if __name__ == "__main__":
    unittest.main()
