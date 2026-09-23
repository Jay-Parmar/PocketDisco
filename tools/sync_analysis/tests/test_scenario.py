from __future__ import annotations

import unittest

from tools.sync_analysis.scenario import ScenarioAnalysisConfig, analyze_scenario
from tools.sync_analysis.telemetry import Observation


CLIENTS = ("phone", "emulator", "windows")
SIGNAL_SHA256 = (
    "e3c4db9cce24fdeb8cfc9131f0240665c54afde2519d99a59b91db98602274f0"
)


def observation(
    measurement: str,
    device_id: str,
    timestamp_ms: float | None,
    *,
    trial_id: str = "trial-01",
    start_id: str = "start-01",
    outcome: str = "ok",
    output_id: str | None = None,
    clock_id: str | None = "coordinator:trial-01",
    clock_uncertainty_ms: float | None = 2,
    target_timestamp_ms: float | None = 10_000,
    failure_reason: str | None = None,
    provider: str = "generated_audio",
    signal_id: str = "generated-click-v1",
    signal_sha256: str = SIGNAL_SHA256,
) -> Observation:
    acoustic = measurement == "acoustic_onset"
    return Observation(
        event_type=measurement,
        trial_id=trial_id,
        start_id=start_id,
        device_id=device_id,
        provider=provider,
        output_category=("virtual" if device_id == "emulator" else "built_in"),
        outcome=outcome,
        timestamp_ms=timestamp_ms,
        clock_id=("capture:mixed-01" if acoustic and outcome == "ok" else clock_id),
        failure_reason=failure_reason,
        schema_version=2,
        scenario_id="mixed-01",
        platform="windows" if device_id == "windows" else "android",
        environment="emulator" if device_id == "emulator" else "physical",
        route_mode="app_fanout" if device_id == "windows" else "single",
        output_id=output_id,
        target_timestamp_ms=None if acoustic else target_timestamp_ms,
        clock_source="external_capture" if acoustic else "coordinator_estimate",
        clock_uncertainty_ms=clock_uncertainty_ms,
        signal_id=signal_id,
        signal_sha256=signal_sha256,
    )


def client_measurements() -> list[Observation]:
    return [
        observation("command_issued", "phone", 10_002),
        observation("command_issued", "emulator", 10_008),
        observation("command_issued", "windows", 10_005),
        observation("playback_observed", "phone", 10_025),
        observation("playback_observed", "emulator", 10_040),
        observation("playback_observed", "windows", 10_031),
    ]


class ScenarioAnalysisTests(unittest.TestCase):
    def test_config_rejects_duplicate_identities(self) -> None:
        with self.assertRaisesRegex(ValueError, "client IDs must be unique"):
            ScenarioAnalysisConfig("mixed-01", ("phone", "phone"))

        with self.assertRaisesRegex(ValueError, "acoustic outputs must be unique"):
            ScenarioAnalysisConfig(
                "mixed-01",
                CLIENTS,
                (("phone", "system"), ("phone", "system")),
            )

    def test_config_requires_two_acoustic_outputs(self) -> None:
        with self.assertRaisesRegex(ValueError, "at least two"):
            ScenarioAnalysisConfig(
                "mixed-01",
                CLIENTS,
                (("phone", "system"),),
            )

    def test_config_requires_a_positive_minimum(self) -> None:
        with self.assertRaisesRegex(ValueError, "minimum_valid_starts"):
            ScenarioAnalysisConfig(
                "mixed-01",
                CLIENTS,
                minimum_valid_starts=0,
            )

    def test_reports_command_player_and_acoustic_timing_separately(self) -> None:
        records = client_measurements()
        records.extend(
            [
                observation("acoustic_onset", "phone", 500, output_id="system"),
                observation(
                    "acoustic_onset",
                    "windows",
                    530,
                    output_id="output-1",
                ),
            ]
        )
        config = ScenarioAnalysisConfig(
            scenario_id="mixed-01",
            expected_clients=CLIENTS,
            expected_acoustic_outputs=(
                ("phone", "system"),
                ("windows", "output-1"),
            ),
        )

        report = analyze_scenario(records, config)

        measurements = {
            item["measurement"]: item for item in report["measurements"]
        }
        self.assertEqual(measurements["command_issued"]["skew_ms"]["p95"], 6)
        self.assertEqual(measurements["playback_observed"]["skew_ms"]["p95"], 15)
        self.assertEqual(measurements["acoustic_onset"]["skew_ms"]["p95"], 30)
        self.assertEqual(report["summary"]["acoustic_status"], "measured")
        self.assertTrue(report["summary"]["command_player_complete"])

    def test_windows_outputs_do_not_count_as_multiple_clients(self) -> None:
        records = client_measurements()
        records.extend(
            [
                observation(
                    "acoustic_onset",
                    "windows",
                    500,
                    output_id="output-1",
                ),
                observation(
                    "acoustic_onset",
                    "windows",
                    510,
                    output_id="output-2",
                ),
            ]
        )

        report = analyze_scenario(
            records,
            ScenarioAnalysisConfig("mixed-01", CLIENTS),
        )

        command = report["measurements"][0]
        acoustic = report["measurements"][2]
        self.assertEqual(command["starts"][0]["identities"], sorted(CLIENTS))
        self.assertEqual(
            acoustic["starts"][0]["identities"],
            ["windows/output-1", "windows/output-2"],
        )

    def test_missing_acoustic_data_is_not_measured(self) -> None:
        report = analyze_scenario(
            client_measurements(),
            ScenarioAnalysisConfig("mixed-01", CLIENTS),
        )

        acoustic = report["measurements"][2]
        self.assertEqual(acoustic["attempted_starts"], 0)
        self.assertEqual(report["summary"]["acoustic_status"], "not_measured")
        self.assertTrue(report["summary"]["command_player_complete"])

    def test_expected_acoustic_outputs_report_partial_capture(self) -> None:
        records = client_measurements()
        records.append(
            observation("acoustic_onset", "phone", 500, output_id="system")
        )
        config = ScenarioAnalysisConfig(
            "mixed-01",
            CLIENTS,
            (("phone", "system"), ("windows", "output-1")),
        )

        report = analyze_scenario(records, config)

        acoustic = report["measurements"][2]
        self.assertEqual(acoustic["failed_starts"], 1)
        self.assertIn("missing_identity", acoustic["failures"][0]["codes"])
        self.assertEqual(report["summary"]["acoustic_status"], "partial")

    def test_missing_duplicate_failure_and_clock_mismatch_are_reported(self) -> None:
        records = [
            observation("command_issued", "phone", 10_002),
            observation("command_issued", "phone", 10_003),
            observation(
                "command_issued",
                "windows",
                None,
                outcome="failure",
                clock_id=None,
                clock_uncertainty_ms=None,
                target_timestamp_ms=None,
                failure_reason="scheduler failed",
            ),
            observation("playback_observed", "phone", 10_025),
            observation(
                "playback_observed",
                "emulator",
                10_040,
                clock_id="coordinator:other",
            ),
            observation("playback_observed", "windows", 10_031),
        ]

        report = analyze_scenario(
            records,
            ScenarioAnalysisConfig("mixed-01", CLIENTS),
        )

        command_codes = report["measurements"][0]["failures"][0]["codes"]
        player_codes = report["measurements"][1]["failures"][0]["codes"]
        self.assertIn("missing_identity", command_codes)
        self.assertIn("duplicate_identity", command_codes)
        self.assertIn("device_failure", command_codes)
        self.assertIn("clock_mismatch", player_codes)
        self.assertFalse(report["summary"]["command_player_complete"])

    def test_uncertainty_is_reported_without_changing_skew(self) -> None:
        records = client_measurements()
        records[1] = observation(
            "command_issued",
            "emulator",
            10_008,
            clock_uncertainty_ms=50,
        )

        report = analyze_scenario(
            records,
            ScenarioAnalysisConfig("mixed-01", CLIENTS),
        )

        command = report["measurements"][0]
        self.assertEqual(command["skew_ms"]["p95"], 6)
        self.assertEqual(command["max_clock_uncertainty_ms"], 50)
        self.assertEqual(command["absolute_target_error_ms"]["max"], 8)

    def test_provider_mismatch_across_measurements_invalidates_start(self) -> None:
        records = client_measurements()
        records[3] = observation(
            "playback_observed",
            "phone",
            10_025,
            provider="licensed_audio",
        )

        report = analyze_scenario(
            records,
            ScenarioAnalysisConfig("mixed-01", CLIENTS),
        )

        command_codes = report["measurements"][0]["failures"][0]["codes"]
        player_codes = report["measurements"][1]["failures"][0]["codes"]
        self.assertIn("provider_mismatch", command_codes)
        self.assertIn("provider_mismatch", player_codes)
        self.assertFalse(report["summary"]["command_player_complete"])

    def test_signal_mismatch_invalidates_scenario_starts(self) -> None:
        mismatches = (
            ("generated-click-other", SIGNAL_SHA256),
            ("generated-click-v1", "0" * 64),
        )
        for signal_id, signal_sha256 in mismatches:
            with self.subTest(signal_id=signal_id, signal_sha256=signal_sha256):
                records = client_measurements()
                records[0] = observation(
                    "command_issued",
                    "phone",
                    10_002,
                    signal_id=signal_id,
                    signal_sha256=signal_sha256,
                )

                report = analyze_scenario(
                    records,
                    ScenarioAnalysisConfig("mixed-01", CLIENTS),
                )

                command_codes = report["measurements"][0]["failures"][0]["codes"]
                player_codes = report["measurements"][1]["failures"][0]["codes"]
                self.assertIn("signal_mismatch", command_codes)
                self.assertIn("signal_mismatch", player_codes)
                self.assertFalse(report["summary"]["command_player_complete"])

    def test_target_mismatch_invalidates_client_measurement(self) -> None:
        records = client_measurements()
        records[1] = observation(
            "command_issued",
            "emulator",
            10_008,
            target_timestamp_ms=10_001,
        )

        report = analyze_scenario(
            records,
            ScenarioAnalysisConfig("mixed-01", CLIENTS),
        )

        command_codes = report["measurements"][0]["failures"][0]["codes"]
        self.assertIn("target_mismatch", command_codes)

    def test_minimum_valid_starts_marks_complete_coverage_incomplete(self) -> None:
        report = analyze_scenario(
            client_measurements(),
            ScenarioAnalysisConfig(
                "mixed-01",
                CLIENTS,
                minimum_valid_starts=2,
            ),
        )

        self.assertFalse(report["summary"]["minimum_starts_met"])
        self.assertFalse(report["summary"]["command_player_complete"])

    def test_rejects_legacy_records(self) -> None:
        record = observation("command_issued", "phone", 10_002)
        object.__setattr__(record, "schema_version", 1)

        with self.assertRaisesRegex(ValueError, "only schema_version 2"):
            analyze_scenario(
                [record],
                ScenarioAnalysisConfig("mixed-01", CLIENTS),
            )


if __name__ == "__main__":
    unittest.main()
