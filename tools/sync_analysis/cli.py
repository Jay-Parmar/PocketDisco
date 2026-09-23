from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Sequence

from .analysis import AnalysisConfig, analyze, render_markdown
from .scenario import ScenarioAnalysisConfig, analyze_scenario, render_scenario_markdown
from .telemetry import (
    EVENT_TYPES,
    OUTPUT_CATEGORIES,
    InputValidationError,
    Observation,
    ValidationIssue,
    read_jsonl,
)


def _positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be at least 1")
    return parsed


def _expected_devices(value: str) -> int:
    parsed = int(value)
    if parsed < 2:
        raise argparse.ArgumentTypeError("must be at least 2")
    return parsed


def _non_negative_float(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed) or parsed < 0:
        raise argparse.ArgumentTypeError("must be finite and non-negative")
    return parsed


def _client_id(value: str) -> str:
    normalized = value.strip()
    if not normalized:
        raise argparse.ArgumentTypeError("must not be empty")
    return normalized


def _acoustic_output(value: str) -> tuple[str, str]:
    if value.count("/") != 1:
        raise argparse.ArgumentTypeError("must use DEVICE_ID/OUTPUT_ID")
    device_id, separator, output_id = value.partition("/")
    if not separator or not device_id.strip() or not output_id.strip():
        raise argparse.ArgumentTypeError("must use DEVICE_ID/OUTPUT_ID")
    return device_id.strip(), output_id.strip()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m tools.sync_analysis",
        description="Analyze synchronized-start JSONL telemetry.",
    )
    parser.add_argument("inputs", nargs="*", help="JSONL telemetry files")
    parser.add_argument(
        "--mode",
        choices=("phase0", "scenario"),
        default="phase0",
        help="analysis mode; defaults to the Phase 0 v1 gate",
    )
    parser.add_argument(
        "-i",
        "--input",
        action="append",
        default=[],
        dest="input_options",
        help="additional JSONL telemetry file, or - for stdin",
    )
    parser.add_argument("--format", choices=("json", "markdown"), default="markdown")
    parser.add_argument("-o", "--output", help="write the report to this file")
    parser.add_argument("--expected-devices", type=_expected_devices, default=2)
    parser.add_argument("--threshold-ms", type=_non_negative_float, default=250.0)
    parser.add_argument("--minimum-starts", type=_positive_int, default=10)
    parser.add_argument("--gate-provider", default="licensed_audio")
    parser.add_argument(
        "--gate-output",
        action="append",
        choices=OUTPUT_CATEGORIES,
        help="target output category; may be repeated; defaults to built_in",
    )
    parser.add_argument(
        "--gate-measurement",
        choices=EVENT_TYPES,
        default="acoustic_onset",
    )
    parser.add_argument("--scenario-id", help="v2 scenario to analyze")
    parser.add_argument(
        "--expected-client",
        action="append",
        default=[],
        type=_client_id,
        help="required v2 client ID; repeat for every client",
    )
    parser.add_argument(
        "--expected-acoustic-output",
        action="append",
        default=[],
        type=_acoustic_output,
        metavar="DEVICE_ID/OUTPUT_ID",
        help="required captured output; repeat for every output",
    )
    return parser


def _load_inputs(paths: list[str]) -> list[Observation]:
    observations: list[Observation] = []
    issues: list[ValidationIssue] = []
    for raw_path in paths:
        if raw_path == "-":
            loaded, found_issues = read_jsonl(sys.stdin, "<stdin>")
        else:
            path = Path(raw_path)
            try:
                with path.open("r", encoding="utf-8") as stream:
                    loaded, found_issues = read_jsonl(stream, str(path))
            except OSError as error:
                loaded = []
                found_issues = [
                    ValidationIssue(str(path), 0, "input_error", str(error))
                ]
        observations.extend(loaded)
        issues.extend(found_issues)
    if issues:
        raise InputValidationError(issues)
    if not observations:
        raise InputValidationError(
            [ValidationIssue("<input>", 0, "empty_input", "no telemetry records found")]
        )
    return observations


def _render_validation_error(error: InputValidationError, output_format: str) -> str:
    if output_format == "json":
        return json.dumps(
            {
                "error": "input_validation_failed",
                "issues": [issue.as_dict() for issue in error.issues],
            },
            indent=2,
            sort_keys=True,
        )
    lines = ["Input validation failed:"]
    for issue in error.issues:
        location = f"{issue.source}:{issue.line}" if issue.line else issue.source
        field = f" [{issue.field}]" if issue.field else ""
        lines.append(f"- {location}{field}: {issue.message}")
    return "\n".join(lines)


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    paths = [*args.inputs, *args.input_options]
    if not paths:
        parser.error("at least one input file is required")
    if paths.count("-") > 1:
        parser.error("stdin may be specified only once")

    try:
        observations = _load_inputs(paths)
    except InputValidationError as error:
        print(_render_validation_error(error, args.format), file=sys.stderr)
        return 2

    if args.mode == "scenario":
        if args.scenario_id is None:
            parser.error("--scenario-id is required in scenario mode")
        try:
            scenario_config = ScenarioAnalysisConfig(
                scenario_id=args.scenario_id,
                expected_clients=tuple(args.expected_client),
                expected_acoustic_outputs=tuple(args.expected_acoustic_output),
            )
            report = analyze_scenario(observations, scenario_config)
        except ValueError as error:
            if args.format == "json":
                rendered_error = json.dumps(
                    {"error": "scenario_analysis_failed", "message": str(error)},
                    indent=2,
                    sort_keys=True,
                )
            else:
                rendered_error = f"Scenario analysis failed: {error}"
            print(rendered_error, file=sys.stderr)
            return 2

        if args.format == "json":
            rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
        else:
            rendered = render_scenario_markdown(report)
        if args.output:
            Path(args.output).write_text(rendered, encoding="utf-8")
        else:
            sys.stdout.write(rendered)

        requested_acoustic = bool(scenario_config.expected_acoustic_outputs)
        requested_coverage_complete = bool(
            report["summary"]["command_player_complete"]
        ) and (
            not requested_acoustic
            or report["summary"]["acoustic_status"] == "measured"
        )
        return 0 if requested_coverage_complete else 1

    if any(observation.schema_version != 1 for observation in observations):
        error = InputValidationError(
            [
                ValidationIssue(
                    "<input>",
                    0,
                    "unsupported_analysis_mode",
                    "schema_version 2 records require --mode scenario",
                    "schema_version",
                )
            ]
        )
        print(_render_validation_error(error, args.format), file=sys.stderr)
        return 2

    config = AnalysisConfig(
        expected_devices=args.expected_devices,
        threshold_ms=args.threshold_ms,
        minimum_valid_starts=args.minimum_starts,
        gate_provider=args.gate_provider,
        gate_outputs=tuple(args.gate_output or ["built_in"]),
        gate_event_type=args.gate_measurement,
    )
    report = analyze(observations, config)
    if args.format == "json":
        rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    else:
        rendered = render_markdown(report)

    if args.output:
        Path(args.output).write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return 0 if report["gate"]["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
