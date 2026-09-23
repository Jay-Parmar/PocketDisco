from __future__ import annotations

import io
import json
import unittest
from pathlib import Path

from tools.sync_analysis.telemetry import InputValidationError, read_jsonl, validate_record


def valid_record() -> dict[str, object]:
    return {
        "schema_version": 1,
        "event_type": "acoustic_onset",
        "trial_id": "trial-01",
        "start_id": "start-01",
        "device_id": "phone-a",
        "provider": "licensed_audio",
        "output_category": "built_in",
        "outcome": "ok",
        "timestamp_ms": 1000.25,
        "clock_id": "capture-1",
    }


class ValidationTests(unittest.TestCase):
    def test_json_schema_file_is_versioned_and_parseable(self) -> None:
        schema_path = Path(__file__).parents[1] / "telemetry.schema.json"

        schema = json.loads(schema_path.read_text(encoding="utf-8"))

        self.assertEqual(schema["properties"]["schema_version"]["const"], 1)
        self.assertIn("acoustic_onset", schema["properties"]["event_type"]["enum"])

    def test_valid_acoustic_observation(self) -> None:
        result = validate_record(valid_record(), "input.jsonl", 1)

        self.assertEqual(result.timestamp_ms, 1000.25)
        self.assertEqual(result.event_type, "acoustic_onset")

    def test_failure_requires_reason_but_not_timestamp(self) -> None:
        record = valid_record()
        record.update({"outcome": "failure"})
        record.pop("timestamp_ms")
        record.pop("clock_id")

        with self.assertRaises(InputValidationError) as context:
            validate_record(record, "input.jsonl", 1)

        self.assertEqual(context.exception.issues[0].field, "failure_reason")

    def test_nan_and_boolean_timestamps_are_rejected(self) -> None:
        for value in (float("nan"), True):
            with self.subTest(value=value):
                record = valid_record()
                record["timestamp_ms"] = value
                with self.assertRaises(InputValidationError):
                    validate_record(record, "input.jsonl", 1)

    def test_reader_collects_json_and_schema_issues(self) -> None:
        stream = io.StringIO('not-json\n{"schema_version": 2}\n')

        observations, issues = read_jsonl(stream, "input.jsonl")

        self.assertEqual(observations, [])
        self.assertGreater(len(issues), 2)
        self.assertEqual(issues[0].code, "invalid_json")


if __name__ == "__main__":
    unittest.main()
