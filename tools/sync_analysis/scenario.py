from __future__ import annotations

import statistics
from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Iterable, Sequence

from .analysis import nearest_rank_percentile
from .telemetry import V2_EVENT_TYPES, V2_SCHEMA_VERSION, Observation


CLIENT_MEASUREMENTS = ("command_issued", "playback_observed")
MEASUREMENT_ORDER = (*CLIENT_MEASUREMENTS, "acoustic_onset")


@dataclass(frozen=True)
class ScenarioAnalysisConfig:
    scenario_id: str
    expected_clients: tuple[str, ...]
    expected_acoustic_outputs: tuple[tuple[str, str], ...] = ()
    minimum_valid_starts: int = 1

    def __post_init__(self) -> None:
        if not self.scenario_id.strip():
            raise ValueError("scenario_id must not be empty")
        if len(self.expected_clients) < 2:
            raise ValueError("at least two expected clients are required")
        if any(not device_id.strip() for device_id in self.expected_clients):
            raise ValueError("expected client IDs must not be empty")
        if len(set(self.expected_clients)) != len(self.expected_clients):
            raise ValueError("expected client IDs must be unique")
        if any(
            not device_id.strip() or not output_id.strip()
            for device_id, output_id in self.expected_acoustic_outputs
        ):
            raise ValueError("expected acoustic output IDs must not be empty")
        if len(set(self.expected_acoustic_outputs)) != len(
            self.expected_acoustic_outputs
        ):
            raise ValueError("expected acoustic outputs must be unique")
        if self.expected_acoustic_outputs and len(self.expected_acoustic_outputs) < 2:
            raise ValueError("at least two expected acoustic outputs are required")
        if self.minimum_valid_starts < 1:
            raise ValueError("minimum_valid_starts must be at least 1")


@dataclass(frozen=True, order=True)
class ScenarioStartKey:
    trial_id: str
    start_id: str


def _number(value: int | float) -> int | float:
    if float(value).is_integer():
        return int(value)
    return float(value)


def _stats(values: Sequence[float]) -> dict[str, int | float | None]:
    if not values:
        return {"median": None, "p95": None, "max": None}
    return {
        "median": _number(float(statistics.median(values))),
        "p95": _number(nearest_rank_percentile(values, 95)),
        "max": _number(float(max(values))),
    }


def _identity(observation: Observation, measurement: str) -> str:
    if measurement == "acoustic_onset":
        assert observation.output_id is not None
        return f"{observation.device_id}/{observation.output_id}"
    return observation.device_id


def _expected_identities(
    measurement: str,
    config: ScenarioAnalysisConfig,
) -> tuple[str, ...] | None:
    if measurement in CLIENT_MEASUREMENTS:
        return config.expected_clients
    if config.expected_acoustic_outputs:
        return tuple(
            f"{device_id}/{output_id}"
            for device_id, output_id in config.expected_acoustic_outputs
        )
    return None


def _failure(
    key: ScenarioStartKey,
    measurement: str,
    observations: list[Observation],
    expected_identities: tuple[str, ...] | None,
    shared_issues: tuple[tuple[str, str], ...],
) -> dict[str, object] | None:
    identities = [_identity(observation, measurement) for observation in observations]
    identity_counts = Counter(identities)
    identity_set = set(identities)
    codes: list[str] = []
    details: list[str] = []

    if expected_identities is None:
        if len(identity_set) < 2:
            codes.append("observation_count")
            details.append("expected at least two distinct acoustic outputs")
    else:
        expected_set = set(expected_identities)
        missing = sorted(expected_set - identity_set)
        unexpected = sorted(identity_set - expected_set)
        if missing:
            codes.append("missing_identity")
            details.append(f"missing observations for: {', '.join(missing)}")
        if unexpected:
            codes.append("unexpected_identity")
            details.append(f"unexpected observations for: {', '.join(unexpected)}")

    duplicates = sorted(
        identity for identity, count in identity_counts.items() if count > 1
    )
    if duplicates:
        codes.append("duplicate_identity")
        details.append(f"duplicate observations for: {', '.join(duplicates)}")

    failed = [observation for observation in observations if observation.outcome == "failure"]
    if failed:
        codes.append("device_failure")
        details.append(
            ", ".join(
                f"{_identity(observation, measurement)}: {observation.failure_reason}"
                for observation in failed
            )
        )

    successful = [observation for observation in observations if observation.outcome == "ok"]
    clock_ids = {observation.clock_id for observation in successful}
    if len(clock_ids) > 1:
        codes.append("clock_mismatch")
        details.append("successful observations do not share one clock_id")

    providers = {observation.provider for observation in observations}
    if len(providers) > 1:
        codes.append("provider_mismatch")
        details.append("observations do not share one provider")

    if measurement in CLIENT_MEASUREMENTS:
        targets = {observation.target_timestamp_ms for observation in successful}
        if len(targets) > 1:
            codes.append("target_mismatch")
            details.append("successful observations do not share one target_timestamp_ms")

    for code, detail in shared_issues:
        if code not in codes:
            codes.append(code)
            details.append(detail)

    if codes:
        return {
            "trial_id": key.trial_id,
            "start_id": key.start_id,
            "codes": codes,
            "details": details,
            "identities": sorted(identity_set),
        }
    return None


def _measurement_report(
    measurement: str,
    start_keys: Sequence[ScenarioStartKey],
    grouped: dict[tuple[str, ScenarioStartKey], list[Observation]],
    config: ScenarioAnalysisConfig,
    include_missing_starts: bool,
    shared_issues: dict[ScenarioStartKey, tuple[tuple[str, str], ...]],
) -> dict[str, object]:
    expected = _expected_identities(measurement, config)
    keys = list(start_keys) if include_missing_starts else sorted(
        key for event_type, key in grouped if event_type == measurement
    )
    skews: list[float] = []
    absolute_target_errors: list[float] = []
    uncertainties: list[float] = []
    starts: list[dict[str, object]] = []
    failures: list[dict[str, object]] = []

    for key in keys:
        observations = grouped.get((measurement, key), [])
        failure = _failure(
            key,
            measurement,
            observations,
            expected,
            shared_issues.get(key, ()),
        )
        if failure is not None:
            failures.append(failure)
            continue

        timestamps = [
            observation.timestamp_ms
            for observation in observations
            if observation.timestamp_ms is not None
        ]
        if len(timestamps) != len(observations):
            failures.append(
                {
                    "trial_id": key.trial_id,
                    "start_id": key.start_id,
                    "codes": ["missing_timestamp"],
                    "details": ["a successful observation has no timestamp_ms"],
                    "identities": sorted(
                        _identity(observation, measurement)
                        for observation in observations
                    ),
                }
            )
            continue

        skew = max(timestamps) - min(timestamps)
        skews.append(skew)
        start: dict[str, object] = {
            "trial_id": key.trial_id,
            "start_id": key.start_id,
            "identities": sorted(
                _identity(observation, measurement) for observation in observations
            ),
            "clock_id": observations[0].clock_id,
            "skew_ms": _number(skew),
            "max_clock_uncertainty_ms": _number(
                max(
                    float(observation.clock_uncertainty_ms)
                    for observation in observations
                    if observation.clock_uncertainty_ms is not None
                )
            ),
        }
        uncertainties.extend(
            float(observation.clock_uncertainty_ms)
            for observation in observations
            if observation.clock_uncertainty_ms is not None
        )
        if measurement in CLIENT_MEASUREMENTS:
            errors = [
                float(observation.timestamp_ms - observation.target_timestamp_ms)
                for observation in observations
                if observation.timestamp_ms is not None
                and observation.target_timestamp_ms is not None
            ]
            absolute_errors = [abs(error) for error in errors]
            absolute_target_errors.extend(absolute_errors)
            start["target_error_ms"] = [
                {
                    "device_id": observation.device_id,
                    "value": _number(
                        float(observation.timestamp_ms - observation.target_timestamp_ms)
                    ),
                }
                for observation in sorted(observations, key=lambda item: item.device_id)
                if observation.timestamp_ms is not None
                and observation.target_timestamp_ms is not None
            ]
        starts.append(start)

    clock_source = (
        "external_capture"
        if measurement == "acoustic_onset"
        else "coordinator_estimate"
    )
    return {
        "measurement": measurement,
        "clock_source": clock_source,
        "attempted_starts": len(keys),
        "valid_starts": len(starts),
        "failed_starts": len(failures),
        "skew_ms": _stats(skews),
        "absolute_target_error_ms": (
            _stats(absolute_target_errors)
            if measurement in CLIENT_MEASUREMENTS
            else None
        ),
        "max_clock_uncertainty_ms": (
            _number(max(uncertainties)) if uncertainties else None
        ),
        "starts": starts,
        "failures": failures,
    }


def analyze_scenario(
    observations: Iterable[Observation],
    config: ScenarioAnalysisConfig,
) -> dict[str, object]:
    observation_list = list(observations)
    invalid_versions = sorted(
        {observation.schema_version for observation in observation_list}
        - {V2_SCHEMA_VERSION}
    )
    if invalid_versions:
        raise ValueError("scenario analysis accepts only schema_version 2 records")

    selected = [
        observation
        for observation in observation_list
        if observation.scenario_id == config.scenario_id
    ]
    if not selected:
        raise ValueError(f"scenario not found: {config.scenario_id}")

    grouped: dict[tuple[str, ScenarioStartKey], list[Observation]] = defaultdict(list)
    providers_by_start: dict[ScenarioStartKey, set[str]] = defaultdict(set)
    all_start_keys: set[ScenarioStartKey] = set()
    for observation in selected:
        if observation.event_type not in V2_EVENT_TYPES:
            raise ValueError(f"invalid scenario measurement: {observation.event_type}")
        key = ScenarioStartKey(observation.trial_id, observation.start_id)
        all_start_keys.add(key)
        providers_by_start[key].add(observation.provider)
        grouped[(observation.event_type, key)].append(observation)

    ordered_starts = sorted(all_start_keys)
    shared_issues = {
        key: (("provider_mismatch", "measurements do not share one provider"),)
        for key, providers in providers_by_start.items()
        if len(providers) > 1
    }
    measurements = [
        _measurement_report(
            measurement,
            ordered_starts,
            grouped,
            config,
            include_missing_starts=(
                measurement in CLIENT_MEASUREMENTS
                or bool(config.expected_acoustic_outputs)
            ),
            shared_issues=shared_issues,
        )
        for measurement in MEASUREMENT_ORDER
    ]
    measurement_index = {
        str(measurement["measurement"]): measurement for measurement in measurements
    }
    client_coverage_complete = all(
        measurement_index[name]["valid_starts"] == len(ordered_starts)
        and measurement_index[name]["failed_starts"] == 0
        for name in CLIENT_MEASUREMENTS
    )
    minimum_starts_met = all(
        measurement_index[name]["valid_starts"] >= config.minimum_valid_starts
        for name in CLIENT_MEASUREMENTS
    )
    client_complete = client_coverage_complete and minimum_starts_met
    acoustic = measurement_index["acoustic_onset"]
    acoustic_records = sum(
        1 for observation in selected if observation.event_type == "acoustic_onset"
    )
    if acoustic_records == 0:
        acoustic_status = "not_measured"
    elif acoustic["failed_starts"]:
        acoustic_status = "partial"
    else:
        acoustic_status = "measured"

    return {
        "schema_version": 2,
        "scenario_id": config.scenario_id,
        "configuration": {
            "expected_clients": list(config.expected_clients),
            "expected_acoustic_outputs": [
                {"device_id": device_id, "output_id": output_id}
                for device_id, output_id in config.expected_acoustic_outputs
            ],
            "minimum_valid_starts": config.minimum_valid_starts,
        },
        "summary": {
            "records": len(selected),
            "attempted_starts": len(ordered_starts),
            "minimum_starts_met": minimum_starts_met,
            "command_player_complete": client_complete,
            "acoustic_status": acoustic_status,
        },
        "measurements": measurements,
    }


def _format_ms(value: object) -> str:
    if value is None:
        return "n/a"
    return f"{float(value):.2f}"


def render_scenario_markdown(report: dict[str, object]) -> str:
    summary = report["summary"]
    measurements = report["measurements"]
    coverage = "complete" if summary["command_player_complete"] else "incomplete"
    lines = [
        "# Mixed sync analysis",
        "",
        f"Scenario: `{report['scenario_id']}`.",
        "",
        (
            f"Records: {summary['records']}. Starts: {summary['attempted_starts']}. "
            f"Minimum valid starts: {report['configuration']['minimum_valid_starts']}. "
            f"Command and player coverage: {coverage}. "
            f"Acoustic status: {summary['acoustic_status']}."
        ),
        "",
        "| Measurement | Clock source | Valid | Failed | Median ms | p95 ms | Max ms | Target p95 ms | Max uncertainty ms |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for measurement in measurements:
        skew = measurement["skew_ms"]
        target = measurement["absolute_target_error_ms"]
        lines.append(
            "| "
            + " | ".join(
                [
                    str(measurement["measurement"]),
                    str(measurement["clock_source"]),
                    str(measurement["valid_starts"]),
                    str(measurement["failed_starts"]),
                    _format_ms(skew["median"]),
                    _format_ms(skew["p95"]),
                    _format_ms(skew["max"]),
                    _format_ms(target["p95"] if target is not None else None),
                    _format_ms(measurement["max_clock_uncertainty_ms"]),
                ]
            )
            + " |"
        )

    lines.extend(
        [
            "",
            "Command timing records when each client issued its playback command. "
            "Player timing records when each client observed its player running. "
            "Only external capture records measure audible onset.",
            "",
            "This report does not evaluate the Phase 0 acoustic gate.",
        ]
    )

    failures = [
        (measurement, failure)
        for measurement in measurements
        for failure in measurement["failures"]
    ]
    if failures:
        lines.extend(["", "## Incomplete starts", ""])
        for measurement, failure in failures:
            codes = ", ".join(failure["codes"])
            lines.append(
                f"- {measurement['measurement']} "
                f"{failure['trial_id']}/{failure['start_id']}: {codes}"
            )

    return "\n".join(lines) + "\n"
