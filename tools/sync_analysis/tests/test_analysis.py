from __future__ import annotations

import unittest

from tools.sync_analysis.analysis import (
    AnalysisConfig,
    analyze,
    nearest_rank_percentile,
    render_markdown,
)
from tools.sync_analysis.telemetry import Observation


def observation(
    trial: int,
    device: str,
    timestamp_ms: float | None,
    *,
    output: str = "built_in",
    event_type: str = "acoustic_onset",
    outcome: str = "ok",
    clock_id: str | None = "capture-1",
    failure_reason: str | None = None,
) -> Observation:
    return Observation(
        event_type=event_type,
        trial_id=f"trial-{trial:02d}",
        start_id="start-01",
        device_id=device,
        provider="licensed_audio",
        output_category=output,
        outcome=outcome,
        timestamp_ms=timestamp_ms,
        clock_id=clock_id,
        failure_reason=failure_reason,
    )


def paired_starts(skews: list[float], output: str = "built_in") -> list[Observation]:
    result = []
    for index, skew in enumerate(skews, start=1):
        base = index * 1000.0
        result.append(observation(index, "phone-a", base, output=output))
        result.append(observation(index, "phone-b", base + skew, output=output))
    return result


class StatisticsTests(unittest.TestCase):
    def test_nearest_rank_p95_uses_tenth_value_for_ten_samples(self) -> None:
        self.assertEqual(nearest_rank_percentile(list(range(1, 11)), 95), 10)

    def test_nearest_rank_rejects_empty_values(self) -> None:
        with self.assertRaisesRegex(ValueError, "must not be empty"):
            nearest_rank_percentile([], 95)


class AnalysisTests(unittest.TestCase):
    def test_gate_passes_with_ten_valid_acoustic_starts(self) -> None:
        records = paired_starts([10, 20, 30, 40, 50, 60, 70, 80, 90, 100])

        report = analyze(records)

        category = report["categories"][0]
        self.assertEqual(category["valid_starts"], 10)
        self.assertEqual(category["failed_starts"], 0)
        self.assertEqual(category["skew_ms"], {"median": 55, "p95": 100, "max": 100})
        self.assertEqual(category["starts"][0]["skew_ms"], 10)
        self.assertEqual(category["starts"][0]["devices"], ["phone-a", "phone-b"])
        self.assertTrue(report["gate"]["passed"])

    def test_gate_fails_when_p95_exceeds_threshold(self) -> None:
        records = paired_starts([10] * 9 + [251])

        report = analyze(records)

        target = report["gate"]["targets"][0]
        self.assertFalse(report["gate"]["passed"])
        self.assertEqual(target["reasons"], ["p95_exceeds_threshold"])

    def test_gate_fails_when_fewer_than_ten_starts_are_valid(self) -> None:
        report = analyze(paired_starts([10] * 9))

        target = report["gate"]["targets"][0]
        self.assertFalse(target["passed"])
        self.assertEqual(target["reasons"], ["insufficient_valid_starts"])

    def test_output_categories_are_reported_separately(self) -> None:
        records = paired_starts([10] * 10)
        records.extend(paired_starts([20] * 10, output="wired"))
        records.extend(paired_starts([300] * 10, output="bluetooth"))

        report = analyze(records)

        categories = {
            category["output_category"]: category for category in report["categories"]
        }
        self.assertEqual(categories["built_in"]["skew_ms"]["p95"], 10)
        self.assertEqual(categories["wired"]["skew_ms"]["p95"], 20)
        self.assertEqual(categories["bluetooth"]["skew_ms"]["p95"], 300)
        self.assertTrue(report["gate"]["passed"])

    def test_explicit_device_failure_invalidates_start(self) -> None:
        records = paired_starts([10] * 10)
        records.extend(
            [
                observation(11, "phone-a", 11000),
                observation(
                    11,
                    "phone-b",
                    None,
                    outcome="failure",
                    clock_id=None,
                    failure_reason="player timeout",
                ),
            ]
        )

        report = analyze(records)

        category = report["categories"][0]
        self.assertEqual(category["valid_starts"], 10)
        self.assertEqual(category["failed_starts"], 1)
        self.assertEqual(category["failures"][0]["codes"], ["device_failure"])

    def test_duplicate_device_and_clock_mismatch_are_failures(self) -> None:
        records = [
            observation(1, "phone-a", 1000, clock_id="capture-1"),
            observation(1, "phone-a", 1010, clock_id="capture-2"),
        ]

        report = analyze(records)

        failure = report["categories"][0]["failures"][0]
        self.assertEqual(failure["codes"], ["duplicate_device", "clock_mismatch"])

    def test_multiple_gate_outputs_are_each_required(self) -> None:
        config = AnalysisConfig(gate_outputs=("built_in", "wired"))

        report = analyze(paired_starts([10] * 10), config)

        self.assertFalse(report["gate"]["passed"])
        self.assertEqual(
            report["gate"]["targets"][1]["reasons"],
            ["insufficient_valid_starts"],
        )

    def test_markdown_contains_stats_and_failure_status(self) -> None:
        report = analyze(paired_starts([10] * 9))

        markdown = render_markdown(report)

        self.assertIn("| licensed_audio | built_in | acoustic_onset |", markdown)
        self.assertIn("**FAIL**", markdown)
        self.assertIn("insufficient_valid_starts", markdown)


if __name__ == "__main__":
    unittest.main()
