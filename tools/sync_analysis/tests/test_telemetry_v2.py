from __future__ import annotations

import json
import unittest
from pathlib import Path

from tools.sync_analysis.telemetry import InputValidationError, validate_record


def command_record() -> dict[str, object]:
    return {
        "schema_version": 2,
        "event_type": "command_issued",
        "scenario_id": "mixed-01",
        "trial_id": "trial-01",
        "start_id": "start-01",
        "device_id": "phone-a",
        "platform": "android",
        "environment": "physical",
        "provider": "generated_audio",
        "output_category": "built_in",
        "route_mode": "single",
        "outcome": "ok",
        "timestamp_ms": 10_004,
        "target_timestamp_ms": 10_000,
        "clock_id": "coordinator:trial-01",
        "clock_source": "coordinator_estimate",
        "clock_uncertainty_ms": 3,
    }


class TelemetryV2ValidationTests(unittest.TestCase):
    def test_schema_file_is_versioned_and_parseable(self) -> None:
        schema_path = Path(__file__).parents[1] / "telemetry-v2.schema.json"

        schema = json.loads(schema_path.read_text(encoding="utf-8"))

        self.assertEqual(schema["properties"]["schema_version"]["const"], 2)
        self.assertIn("command_issued", schema["properties"]["event_type"]["enum"])

    def test_valid_client_command(self) -> None:
        observation = validate_record(command_record(), "input.jsonl", 1)

        self.assertEqual(observation.schema_version, 2)
        self.assertEqual(observation.scenario_id, "mixed-01")
        self.assertEqual(observation.target_timestamp_ms, 10_000)
        self.assertEqual(observation.clock_uncertainty_ms, 3)

    def test_valid_acoustic_onset_uses_external_capture(self) -> None:
        record = command_record()
        record.update(
            {
                "event_type": "acoustic_onset",
                "output_id": "system",
                "timestamp_ms": 500.25,
                "clock_id": "capture:mixed-01",
                "clock_source": "external_capture",
                "clock_uncertainty_ms": 0.25,
            }
        )
        record.pop("target_timestamp_ms")

        observation = validate_record(record, "input.jsonl", 1)

        self.assertEqual(observation.output_id, "system")
        self.assertEqual(observation.clock_source, "external_capture")

    def test_acoustic_onset_requires_output_id(self) -> None:
        record = command_record()
        record.update(
            {
                "event_type": "acoustic_onset",
                "clock_id": "capture:mixed-01",
                "clock_source": "external_capture",
            }
        )
        record.pop("target_timestamp_ms")

        with self.assertRaises(InputValidationError) as context:
            validate_record(record, "input.jsonl", 1)

        self.assertIn("output_id", {issue.field for issue in context.exception.issues})

    def test_acoustic_onset_rejects_target_timestamp(self) -> None:
        record = command_record()
        record.update(
            {
                "event_type": "acoustic_onset",
                "output_id": "system",
                "clock_id": "capture:mixed-01",
                "clock_source": "external_capture",
            }
        )

        with self.assertRaises(InputValidationError) as context:
            validate_record(record, "input.jsonl", 1)

        self.assertIn(
            "target_timestamp_ms",
            {issue.field for issue in context.exception.issues},
        )

    def test_client_measurement_rejects_output_id_and_capture_clock(self) -> None:
        record = command_record()
        record["output_id"] = "output-1"
        record["clock_source"] = "external_capture"

        with self.assertRaises(InputValidationError) as context:
            validate_record(record, "input.jsonl", 1)

        fields = [issue.field for issue in context.exception.issues]
        self.assertIn("output_id", fields)
        self.assertIn("clock_source", fields)

    def test_failure_requires_reason_without_timing_fields(self) -> None:
        record = command_record()
        record["outcome"] = "failure"
        for field in (
            "timestamp_ms",
            "target_timestamp_ms",
            "clock_id",
            "clock_source",
            "clock_uncertainty_ms",
        ):
            record.pop(field)

        with self.assertRaises(InputValidationError) as context:
            validate_record(record, "input.jsonl", 1)

        self.assertEqual(context.exception.issues[0].field, "failure_reason")

    def test_unsupported_version_is_rejected(self) -> None:
        record = command_record()
        record["schema_version"] = 3

        with self.assertRaises(InputValidationError) as context:
            validate_record(record, "input.jsonl", 1)

        self.assertEqual(context.exception.issues[0].code, "unsupported_schema_version")


if __name__ == "__main__":
    unittest.main()
