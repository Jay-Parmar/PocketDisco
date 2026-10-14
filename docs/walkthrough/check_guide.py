from __future__ import annotations

import argparse
import json
from pathlib import Path

import pymupdf

HERE = Path(__file__).resolve().parent
EXPECTED_HEADINGS = (
    "Your room.",
    "Know what plays.",
    "Then get together.",
    "A room in five minutes.",
    "This phone only.",
    "audio session.",
    "Try one nearby link.",
    "Keep your place.",
    "acceptance lap.",
    "What comes next.",
)


def verify(path: Path, output: Path | None = None) -> dict:
    report = {"pages": 0, "embedded_screenshots": 0, "checks": []}
    with pymupdf.open(path) as document:
        if len(document) != 10:
            raise ValueError("The guide must have ten pages")
        if len(document.get_toc()) != 10:
            raise ValueError("Each page needs a navigation bookmark")
        widgets = list(document[8].widgets())
        if len(widgets) != 11:
            raise ValueError("Checklist needs nine checkboxes and two note fields")
        if (
            sum(
                widget.field_type == pymupdf.PDF_WIDGET_TYPE_CHECKBOX
                for widget in widgets
            )
            != 9
        ):
            raise ValueError("Checklist checkbox count changed")
        for index, page in enumerate(document):
            text = page.get_text()
            if EXPECTED_HEADINGS[index] not in text:
                raise ValueError(f"Missing page heading: {index + 1}")
            if "\u2014" in text:
                raise ValueError(f"Unexpected em dash: page {index + 1}")
            for word in page.get_text("words"):
                x0, y0, x1, y1 = word[:4]
                if x0 < 0 or y0 < 0 or x1 > page.rect.width or y1 > page.rect.height:
                    raise ValueError(f"Text outside page {index + 1}: {word[4]}")
            report["embedded_screenshots"] += len(page.get_images())
            if output:
                page.get_pixmap(matrix=pymupdf.Matrix(1.5, 1.5)).save(
                    output / f"page-{index + 1:02}.png"
                )
            report["pages"] += 1
        whole_text = " ".join(" ".join(page.get_text() for page in document).split())
        for phrase in (
            "http://127.0.0.1:8000",
            "not a real Bluetooth radio",
            "never starts",
            "50080 / 50443",
            "not configured yet",
            "does not change that",
            "not cross-phone",
            "Host paired Bluetooth",
            "Receive paired Bluetooth",
            "New session code",
            "Start capture",
            "Play allowed",
            "Stop session",
        ):
            if phrase not in whole_text:
                raise ValueError(f"Missing boundary: {phrase}")
        report["checks"] = [
            "Ten pages and ten bookmarks",
            "Expected page headings are searchable",
            "All extracted text lies within the page",
            "Key capability and deployment boundaries are present",
            "Nine interactive checkboxes and two note fields",
            "No em dashes",
        ]
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "pdf", type=Path, nargs="?", default=HERE / "PocketDisco-Walkthrough.pdf"
    )
    parser.add_argument("--render-dir", type=Path)
    args = parser.parse_args()
    if args.render_dir:
        args.render_dir.mkdir(parents=True, exist_ok=True)
    report = verify(args.pdf, args.render_dir)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
