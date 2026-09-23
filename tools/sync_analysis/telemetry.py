from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, TextIO


SCHEMA_VERSION = 1
EVENT_TYPES = ("playback_start", "acoustic_onset")
OUTPUT_CATEGORIES = ("built_in", "wired", "bluetooth")
OUTCOMES = ("ok", "failure")
V2_SCHEMA_VERSION = 2
V2_EVENT_TYPES = ("command_issued", "playback_observed", "acoustic_onset")
V2_OUTPUT_CATEGORIES = (
    "built_in",
    "wired",
    "bluetooth",
    "usb",
    "virtual",
    "mixed",
)
PLATFORMS = ("android", "windows")
ENVIRONMENTS = ("physical", "emulator")
ROUTE_MODES = ("single", "system_group", "app_fanout")
CLOCK_SOURCES = ("coordinator_estimate", "external_capture")
SHA256_PATTERN = re.compile(r"[0-9a-fA-F]{64}")


@dataclass(frozen=True)
class Observation:
    event_type: str
    trial_id: str
    start_id: str
    device_id: str
    provider: str
    output_category: str
    outcome: str
    timestamp_ms: float | None
    clock_id: str | None
    failure_reason: str | None
    schema_version: int = SCHEMA_VERSION
    scenario_id: str | None = None
    platform: str | None = None
    environment: str | None = None
    route_mode: str | None = None
    output_id: str | None = None
    target_timestamp_ms: float | None = None
    clock_source: str | None = None
    clock_uncertainty_ms: float | None = None
    signal_id: str | None = None
    signal_sha256: str | None = None


@dataclass(frozen=True)
class ValidationIssue:
    source: str
    line: int
    code: str
    message: str
    field: str | None = None

    def as_dict(self) -> dict[str, object]:
        result: dict[str, object] = {
            "source": self.source,
            "line": self.line,
            "code": self.code,
            "message": self.message,
        }
        if self.field is not None:
            result["field"] = self.field
        return result


class InputValidationError(ValueError):
    def __init__(self, issues: Iterable[ValidationIssue]):
        self.issues = tuple(issues)
        super().__init__(f"telemetry contains {len(self.issues)} validation issue(s)")


def _required_string(
    record: dict[str, object],
    field: str,
    source: str,
    line: int,
    issues: list[ValidationIssue],
) -> str | None:
    value = record.get(field)
    if not isinstance(value, str) or not value.strip():
        issues.append(
            ValidationIssue(
                source,
                line,
                "invalid_field",
                f"{field} must be a non-empty string",
                field,
            )
        )
        return None
    return value.strip()


def _choice(
    value: str | None,
    field: str,
    allowed: tuple[str, ...],
    source: str,
    line: int,
    issues: list[ValidationIssue],
) -> str | None:
    if value is None:
        return None
    if value not in allowed:
        allowed_text = ", ".join(allowed)
        issues.append(
            ValidationIssue(
                source,
                line,
                "invalid_choice",
                f"{field} must be one of: {allowed_text}",
                field,
            )
        )
        return None
    return value


def _non_negative_number(
    record: dict[str, object],
    field: str,
    source: str,
    line: int,
    issues: list[ValidationIssue],
) -> float | None:
    value = record.get(field)
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value < 0
    ):
        issues.append(
            ValidationIssue(
                source,
                line,
                "invalid_field",
                f"{field} must be a finite non-negative number",
                field,
            )
        )
        return None
    return float(value)


def _required_sha256(
    record: dict[str, object],
    field: str,
    source: str,
    line: int,
    issues: list[ValidationIssue],
) -> str | None:
    value = _required_string(record, field, source, line, issues)
    if value is None:
        return None
    if SHA256_PATTERN.fullmatch(value) is None:
        issues.append(
            ValidationIssue(
                source,
                line,
                "invalid_field",
                f"{field} must be a 64-character hexadecimal SHA-256 digest",
                field,
            )
        )
        return None
    return value.lower()


def _validate_v1_record(
    record: dict[str, object],
    source: str,
    line: int,
) -> Observation:
    issues: list[ValidationIssue] = []
    event_type = _choice(
        _required_string(record, "event_type", source, line, issues),
        "event_type",
        EVENT_TYPES,
        source,
        line,
        issues,
    )
    trial_id = _required_string(record, "trial_id", source, line, issues)
    start_id = _required_string(record, "start_id", source, line, issues)
    device_id = _required_string(record, "device_id", source, line, issues)
    provider = _required_string(record, "provider", source, line, issues)
    output_category = _choice(
        _required_string(record, "output_category", source, line, issues),
        "output_category",
        OUTPUT_CATEGORIES,
        source,
        line,
        issues,
    )
    outcome = _choice(
        _required_string(record, "outcome", source, line, issues),
        "outcome",
        OUTCOMES,
        source,
        line,
        issues,
    )

    timestamp_ms: float | None = None
    clock_id: str | None = None
    failure_reason: str | None = None
    if outcome == "ok":
        timestamp_value = record.get("timestamp_ms")
        if (
            isinstance(timestamp_value, bool)
            or not isinstance(timestamp_value, (int, float))
            or not math.isfinite(timestamp_value)
            or timestamp_value < 0
        ):
            issues.append(
                ValidationIssue(
                    source,
                    line,
                    "invalid_field",
                    "timestamp_ms must be a finite non-negative number for an ok outcome",
                    "timestamp_ms",
                )
            )
        else:
            timestamp_ms = float(timestamp_value)
        clock_id = _required_string(record, "clock_id", source, line, issues)
    elif outcome == "failure":
        failure_reason = _required_string(record, "failure_reason", source, line, issues)

    if issues:
        raise InputValidationError(issues)

    assert event_type is not None
    assert trial_id is not None
    assert start_id is not None
    assert device_id is not None
    assert provider is not None
    assert output_category is not None
    assert outcome is not None
    return Observation(
        event_type=event_type,
        trial_id=trial_id,
        start_id=start_id,
        device_id=device_id,
        provider=provider,
        output_category=output_category,
        outcome=outcome,
        timestamp_ms=timestamp_ms,
        clock_id=clock_id,
        failure_reason=failure_reason,
    )


def _validate_v2_record(
    record: dict[str, object],
    source: str,
    line: int,
) -> Observation:
    issues: list[ValidationIssue] = []
    event_type = _choice(
        _required_string(record, "event_type", source, line, issues),
        "event_type",
        V2_EVENT_TYPES,
        source,
        line,
        issues,
    )
    scenario_id = _required_string(record, "scenario_id", source, line, issues)
    trial_id = _required_string(record, "trial_id", source, line, issues)
    start_id = _required_string(record, "start_id", source, line, issues)
    device_id = _required_string(record, "device_id", source, line, issues)
    platform = _choice(
        _required_string(record, "platform", source, line, issues),
        "platform",
        PLATFORMS,
        source,
        line,
        issues,
    )
    environment = _choice(
        _required_string(record, "environment", source, line, issues),
        "environment",
        ENVIRONMENTS,
        source,
        line,
        issues,
    )
    provider = _required_string(record, "provider", source, line, issues)
    signal_id = _required_string(record, "signal_id", source, line, issues)
    signal_sha256 = _required_sha256(
        record,
        "signal_sha256",
        source,
        line,
        issues,
    )
    output_category = _choice(
        _required_string(record, "output_category", source, line, issues),
        "output_category",
        V2_OUTPUT_CATEGORIES,
        source,
        line,
        issues,
    )
    route_mode = _choice(
        _required_string(record, "route_mode", source, line, issues),
        "route_mode",
        ROUTE_MODES,
        source,
        line,
        issues,
    )
    outcome = _choice(
        _required_string(record, "outcome", source, line, issues),
        "outcome",
        OUTCOMES,
        source,
        line,
        issues,
    )

    output_id: str | None = None
    if event_type == "acoustic_onset":
        output_id = _required_string(record, "output_id", source, line, issues)
        if "target_timestamp_ms" in record:
            issues.append(
                ValidationIssue(
                    source,
                    line,
                    "invalid_field",
                    "target_timestamp_ms is not allowed for acoustic_onset",
                    "target_timestamp_ms",
                )
            )
    elif event_type in {"command_issued", "playback_observed"} and "output_id" in record:
        issues.append(
            ValidationIssue(
                source,
                line,
                "invalid_field",
                f"output_id is not allowed for {event_type}",
                "output_id",
            )
        )

    timestamp_ms: float | None = None
    target_timestamp_ms: float | None = None
    clock_id: str | None = None
    clock_source: str | None = None
    clock_uncertainty_ms: float | None = None
    failure_reason: str | None = None
    if outcome == "ok":
        timestamp_ms = _non_negative_number(
            record,
            "timestamp_ms",
            source,
            line,
            issues,
        )
        clock_id = _required_string(record, "clock_id", source, line, issues)
        clock_source = _choice(
            _required_string(record, "clock_source", source, line, issues),
            "clock_source",
            CLOCK_SOURCES,
            source,
            line,
            issues,
        )
        clock_uncertainty_ms = _non_negative_number(
            record,
            "clock_uncertainty_ms",
            source,
            line,
            issues,
        )
        if event_type in {"command_issued", "playback_observed"}:
            target_timestamp_ms = _non_negative_number(
                record,
                "target_timestamp_ms",
                source,
                line,
                issues,
            )
            if clock_source is not None and clock_source != "coordinator_estimate":
                issues.append(
                    ValidationIssue(
                        source,
                        line,
                        "invalid_choice",
                        f"clock_source must be coordinator_estimate for {event_type}",
                        "clock_source",
                    )
                )
        elif event_type == "acoustic_onset":
            if clock_source is not None and clock_source != "external_capture":
                issues.append(
                    ValidationIssue(
                        source,
                        line,
                        "invalid_choice",
                        "clock_source must be external_capture for acoustic_onset",
                        "clock_source",
                    )
                )
    elif outcome == "failure":
        failure_reason = _required_string(record, "failure_reason", source, line, issues)

    if issues:
        raise InputValidationError(issues)

    assert event_type is not None
    assert scenario_id is not None
    assert trial_id is not None
    assert start_id is not None
    assert device_id is not None
    assert platform is not None
    assert environment is not None
    assert provider is not None
    assert signal_id is not None
    assert signal_sha256 is not None
    assert output_category is not None
    assert route_mode is not None
    assert outcome is not None
    return Observation(
        event_type=event_type,
        trial_id=trial_id,
        start_id=start_id,
        device_id=device_id,
        provider=provider,
        output_category=output_category,
        outcome=outcome,
        timestamp_ms=timestamp_ms,
        clock_id=clock_id,
        failure_reason=failure_reason,
        schema_version=V2_SCHEMA_VERSION,
        scenario_id=scenario_id,
        platform=platform,
        environment=environment,
        route_mode=route_mode,
        output_id=output_id,
        target_timestamp_ms=target_timestamp_ms,
        clock_source=clock_source,
        clock_uncertainty_ms=clock_uncertainty_ms,
        signal_id=signal_id,
        signal_sha256=signal_sha256,
    )


def validate_record(record: object, source: str, line: int) -> Observation:
    if not isinstance(record, dict):
        raise InputValidationError(
            [
                ValidationIssue(
                    source,
                    line,
                    "invalid_record",
                    "each JSONL line must contain an object",
                )
            ]
        )

    version = record.get("schema_version")
    if isinstance(version, bool) or not isinstance(version, int):
        raise InputValidationError(
            [
                ValidationIssue(
                    source,
                    line,
                    "unsupported_schema_version",
                    "schema_version must be 1 or 2",
                    "schema_version",
                )
            ]
        )
    if version == SCHEMA_VERSION:
        return _validate_v1_record(record, source, line)
    if version == V2_SCHEMA_VERSION:
        return _validate_v2_record(record, source, line)
    raise InputValidationError(
        [
            ValidationIssue(
                source,
                line,
                "unsupported_schema_version",
                "schema_version must be 1 or 2",
                "schema_version",
            )
        ]
    )


def read_jsonl(stream: TextIO, source: str) -> tuple[list[Observation], list[ValidationIssue]]:
    observations: list[Observation] = []
    issues: list[ValidationIssue] = []
    for line_number, raw_line in enumerate(stream, start=1):
        if not raw_line.strip():
            continue
        try:
            record = json.loads(raw_line)
        except json.JSONDecodeError as error:
            issues.append(
                ValidationIssue(
                    source,
                    line_number,
                    "invalid_json",
                    f"invalid JSON at column {error.colno}: {error.msg}",
                )
            )
            continue
        try:
            observations.append(validate_record(record, source, line_number))
        except InputValidationError as error:
            issues.extend(error.issues)
    return observations, issues


def load_observations(paths: Iterable[str | Path]) -> list[Observation]:
    observations: list[Observation] = []
    issues: list[ValidationIssue] = []
    for raw_path in paths:
        path = Path(raw_path)
        try:
            with path.open("r", encoding="utf-8") as stream:
                loaded, found_issues = read_jsonl(stream, str(path))
        except OSError as error:
            issues.append(
                ValidationIssue(
                    str(path),
                    0,
                    "input_error",
                    str(error),
                )
            )
            continue
        observations.extend(loaded)
        issues.extend(found_issues)
    if issues:
        raise InputValidationError(issues)
    if not observations:
        raise InputValidationError(
            [ValidationIssue("<input>", 0, "empty_input", "no telemetry records found")]
        )
    return observations
