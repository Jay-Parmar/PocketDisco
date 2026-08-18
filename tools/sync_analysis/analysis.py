from __future__ import annotations

import math
import statistics
from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Iterable, Sequence

from .telemetry import EVENT_TYPES, OUTPUT_CATEGORIES, Observation


@dataclass(frozen=True)
class AnalysisConfig:
    expected_devices: int = 2
    threshold_ms: float = 250.0
    minimum_valid_starts: int = 10
    gate_provider: str = "licensed_audio"
    gate_outputs: tuple[str, ...] = ("built_in",)
    gate_event_type: str = "acoustic_onset"

    def __post_init__(self) -> None:
        if self.expected_devices < 2:
            raise ValueError("expected_devices must be at least 2")
        if not math.isfinite(self.threshold_ms) or self.threshold_ms < 0:
            raise ValueError("threshold_ms must be a finite non-negative number")
        if self.minimum_valid_starts < 1:
            raise ValueError("minimum_valid_starts must be at least 1")
        if not self.gate_provider.strip():
            raise ValueError("gate_provider must not be empty")
        if not self.gate_outputs:
            raise ValueError("at least one gate output is required")
        invalid_outputs = set(self.gate_outputs).difference(OUTPUT_CATEGORIES)
        if invalid_outputs:
            raise ValueError(f"invalid gate output: {sorted(invalid_outputs)[0]}")
        if len(set(self.gate_outputs)) != len(self.gate_outputs):
            raise ValueError("gate outputs must be unique")
        if self.gate_event_type not in EVENT_TYPES:
            raise ValueError(f"invalid gate event type: {self.gate_event_type}")


@dataclass(frozen=True, order=True)
class CategoryKey:
    provider: str
    output_category: str
    event_type: str


@dataclass(frozen=True, order=True)
class StartKey:
    category: CategoryKey
    trial_id: str
    start_id: str


def nearest_rank_percentile(values: Sequence[float], percentile: float) -> float:
    if not values:
        raise ValueError("values must not be empty")
    if not 0 < percentile <= 100:
        raise ValueError("percentile must be greater than 0 and no greater than 100")
    ordered = sorted(values)
    rank = math.ceil((percentile / 100) * len(ordered))
    return float(ordered[rank - 1])


def _number(value: float) -> int | float:
    if value.is_integer():
        return int(value)
    return value


def _failure(
    key: StartKey,
    observations: list[Observation],
    expected_devices: int,
) -> dict[str, object] | None:
    devices = [observation.device_id for observation in observations]
    device_counts = Counter(devices)
    codes: list[str] = []
    details: list[str] = []

    if len(observations) != expected_devices:
        codes.append("observation_count")
        details.append(f"expected {expected_devices} observations, found {len(observations)}")

    duplicate_devices = sorted(device for device, count in device_counts.items() if count > 1)
    if duplicate_devices:
        codes.append("duplicate_device")
        details.append(f"duplicate observations for: {', '.join(duplicate_devices)}")

    failed = [observation for observation in observations if observation.outcome == "failure"]
    if failed:
        codes.append("device_failure")
        failure_text = ", ".join(
            f"{observation.device_id}: {observation.failure_reason}" for observation in failed
        )
        details.append(failure_text)

    successful = [observation for observation in observations if observation.outcome == "ok"]
    clock_ids = {observation.clock_id for observation in successful}
    if len(clock_ids) > 1:
        codes.append("clock_mismatch")
        details.append("successful observations do not share one clock_id")

    if codes:
        return {
            "trial_id": key.trial_id,
            "start_id": key.start_id,
            "codes": codes,
            "details": details,
            "devices": sorted(set(devices)),
        }
    return None


def _category_report(
    key: CategoryKey,
    groups: list[tuple[StartKey, list[Observation]]],
    expected_devices: int,
) -> dict[str, object]:
    skews: list[float] = []
    starts: list[dict[str, object]] = []
    failures: list[dict[str, object]] = []
    for start_key, observations in sorted(groups, key=lambda item: item[0]):
        failure = _failure(start_key, observations, expected_devices)
        if failure is not None:
            failures.append(failure)
            continue
        timestamps = [
            observation.timestamp_ms
            for observation in observations
            if observation.timestamp_ms is not None
        ]
        if len(timestamps) != expected_devices:
            failures.append(
                {
                    "trial_id": start_key.trial_id,
                    "start_id": start_key.start_id,
                    "codes": ["missing_timestamp"],
                    "details": ["a successful observation has no timestamp_ms"],
                    "devices": sorted({observation.device_id for observation in observations}),
                }
            )
            continue
        skew = max(timestamps) - min(timestamps)
        skews.append(skew)
        starts.append(
            {
                "trial_id": start_key.trial_id,
                "start_id": start_key.start_id,
                "devices": sorted(observation.device_id for observation in observations),
                "clock_id": observations[0].clock_id,
                "skew_ms": _number(skew),
            }
        )

    if skews:
        stats: dict[str, int | float | None] = {
            "median": _number(float(statistics.median(skews))),
            "p95": _number(nearest_rank_percentile(skews, 95)),
            "max": _number(float(max(skews))),
        }
    else:
        stats = {"median": None, "p95": None, "max": None}

    return {
        "provider": key.provider,
        "output_category": key.output_category,
        "measurement": key.event_type,
        "attempted_starts": len(groups),
        "valid_starts": len(skews),
        "failed_starts": len(failures),
        "skew_ms": stats,
        "starts": starts,
        "failures": failures,
    }


def _output_sort_key(output: str) -> tuple[int, str]:
    try:
        return OUTPUT_CATEGORIES.index(output), output
    except ValueError:
        return len(OUTPUT_CATEGORIES), output


def analyze(
    observations: Iterable[Observation],
    config: AnalysisConfig | None = None,
) -> dict[str, object]:
    selected_config = config or AnalysisConfig()
    observation_list = list(observations)
    grouped: dict[StartKey, list[Observation]] = defaultdict(list)
    for observation in observation_list:
        category = CategoryKey(
            provider=observation.provider,
            output_category=observation.output_category,
            event_type=observation.event_type,
        )
        key = StartKey(category, observation.trial_id, observation.start_id)
        grouped[key].append(observation)

    category_groups: dict[CategoryKey, list[tuple[StartKey, list[Observation]]]] = defaultdict(list)
    for key, items in grouped.items():
        category_groups[key.category].append((key, items))

    category_keys = sorted(
        category_groups,
        key=lambda key: (
            key.provider,
            _output_sort_key(key.output_category),
            key.event_type,
        ),
    )
    categories = [
        _category_report(key, category_groups[key], selected_config.expected_devices)
        for key in category_keys
    ]
    category_index = {
        (item["provider"], item["output_category"], item["measurement"]): item
        for item in categories
    }

    gate_targets: list[dict[str, object]] = []
    for output in selected_config.gate_outputs:
        category = category_index.get(
            (selected_config.gate_provider, output, selected_config.gate_event_type)
        )
        valid_starts = int(category["valid_starts"]) if category is not None else 0
        p95 = category["skew_ms"]["p95"] if category is not None else None
        reasons: list[str] = []
        if valid_starts < selected_config.minimum_valid_starts:
            reasons.append("insufficient_valid_starts")
        if p95 is not None and float(p95) > selected_config.threshold_ms:
            reasons.append("p95_exceeds_threshold")
        gate_targets.append(
            {
                "output_category": output,
                "valid_starts": valid_starts,
                "p95_ms": p95,
                "passed": not reasons,
                "reasons": reasons,
            }
        )

    total_valid = sum(int(category["valid_starts"]) for category in categories)
    total_failed = sum(int(category["failed_starts"]) for category in categories)
    return {
        "schema_version": 1,
        "configuration": {
            "expected_devices": selected_config.expected_devices,
            "threshold_ms": _number(float(selected_config.threshold_ms)),
            "minimum_valid_starts": selected_config.minimum_valid_starts,
        },
        "summary": {
            "records": len(observation_list),
            "attempted_starts": len(grouped),
            "valid_starts": total_valid,
            "failed_starts": total_failed,
        },
        "categories": categories,
        "gate": {
            "name": "phase_0_licensed_audio",
            "provider": selected_config.gate_provider,
            "measurement": selected_config.gate_event_type,
            "outputs": list(selected_config.gate_outputs),
            "threshold_ms": _number(float(selected_config.threshold_ms)),
            "minimum_valid_starts": selected_config.minimum_valid_starts,
            "passed": all(bool(target["passed"]) for target in gate_targets),
            "targets": gate_targets,
        },
    }


def _format_ms(value: object) -> str:
    if value is None:
        return "n/a"
    return f"{float(value):.2f}"


def render_markdown(report: dict[str, object]) -> str:
    summary = report["summary"]
    categories = report["categories"]
    gate = report["gate"]
    lines = [
        "# Sync analysis",
        "",
        (
            f"Records: {summary['records']}. Valid starts: {summary['valid_starts']}. "
            f"Failed starts: {summary['failed_starts']}."
        ),
        "",
        "| Provider | Output | Measurement | Valid | Failed | Median ms | p95 ms | Max ms |",
        "|---|---|---|---:|---:|---:|---:|---:|",
    ]
    for category in categories:
        stats = category["skew_ms"]
        lines.append(
            "| "
            + " | ".join(
                [
                    str(category["provider"]),
                    str(category["output_category"]),
                    str(category["measurement"]),
                    str(category["valid_starts"]),
                    str(category["failed_starts"]),
                    _format_ms(stats["median"]),
                    _format_ms(stats["p95"]),
                    _format_ms(stats["max"]),
                ]
            )
            + " |"
        )

    status = "PASS" if gate["passed"] else "FAIL"
    lines.extend(
        [
            "",
            "## Phase 0 gate",
            "",
            (
                f"**{status}** for {gate['provider']} {gate['measurement']} at "
                f"p95 <= {_format_ms(gate['threshold_ms'])} ms with at least "
                f"{gate['minimum_valid_starts']} valid starts per target output."
            ),
            "",
            "| Output | Valid | p95 ms | Result | Reasons |",
            "|---|---:|---:|---|---|",
        ]
    )
    for target in gate["targets"]:
        result = "pass" if target["passed"] else "fail"
        reasons = ", ".join(target["reasons"]) or "none"
        lines.append(
            f"| {target['output_category']} | {target['valid_starts']} | "
            f"{_format_ms(target['p95_ms'])} | {result} | {reasons} |"
        )

    failures = [
        (category, failure)
        for category in categories
        for failure in category["failures"]
    ]
    if failures:
        lines.extend(["", "## Failed starts", ""])
        for category, failure in failures:
            codes = ", ".join(failure["codes"])
            lines.append(
                f"- {category['provider']}/{category['output_category']}/"
                f"{category['measurement']} {failure['trial_id']}/{failure['start_id']}: {codes}"
            )

    return "\n".join(lines) + "\n"
