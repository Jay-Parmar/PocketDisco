from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Sequence

from .analysis import AnalysisConfig, analyze, render_markdown
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


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m tools.sync_analysis",
        description="Analyze synchronized-start JSONL telemetry.",
    )
    parser.add_argument("inputs", nargs="*", help="JSONL telemetry files")
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
