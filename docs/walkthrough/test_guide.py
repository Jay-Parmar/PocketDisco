import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import pymupdf
from build_guide import HERE, Guide, read_edition
from check_guide import verify

REPO = HERE.parents[1]
SCRATCH = REPO / ".local-tools" / "walkthrough-tests"


class GuideTests(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.folder = tempfile.TemporaryDirectory(dir=SCRATCH)
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name)
        self.edition = json.loads((HERE / "edition.json").read_text(encoding="utf-8"))

    def write_edition(self, edition):
        path = self.path / "edition.json"
        path.write_text(json.dumps(edition), encoding="utf-8")
        return path

    def test_final_build_requires_verified_edition(self):
        self.edition["final"] = False
        with self.assertRaisesRegex(ValueError, "Final device evidence"):
            read_edition(self.write_edition(self.edition), draft=False)

    def test_final_build_requires_real_screenshots(self):
        self.edition.update(final=True, screenshots={})
        with self.assertRaisesRegex(ValueError, "Missing real screenshot"):
            read_edition(self.write_edition(self.edition), draft=False)

    def test_screenshots_cannot_escape_asset_directory(self):
        self.edition["screenshots"] = {
            "welcome": {
                "file": "../../README.md",
                "contains_invite": False,
                "description": "Not an image",
            }
        }
        with self.assertRaisesRegex(ValueError, "must exist under"):
            read_edition(self.write_edition(self.edition), draft=True)

    def asset_edition(self):
        assets = HERE / "assets"
        assets.mkdir(exist_ok=True)
        folder = tempfile.TemporaryDirectory(dir=assets)
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / "fixture.txt"
        path.write_bytes(b"not a screenshot")
        item = {
            "file": path.relative_to(HERE).as_posix(),
            "contains_invite": False,
            "description": "Test fixture",
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        self.edition["screenshots"] = {"welcome": item}
        return item

    def test_screenshot_requires_no_invite_review(self):
        item = self.asset_edition()
        item.pop("contains_invite")
        with self.assertRaisesRegex(ValueError, "no-invite review"):
            read_edition(self.write_edition(self.edition), draft=True)

    def test_changed_screenshot_is_rejected(self):
        item = self.asset_edition()
        item["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "hash does not match"):
            read_edition(self.write_edition(self.edition), draft=True)

    def test_result_groups_fit_the_evidence_layout(self):
        self.edition["checks"].append({"label": "Extra", "result": "Unused"})
        with self.assertRaisesRegex(ValueError, "three concise result groups"):
            read_edition(self.write_edition(self.edition), draft=True)

    def test_draft_uses_placeholders_instead_of_fake_screenshots(self):
        edition = copy.deepcopy(self.edition)
        edition.update(final=False, screenshots={})
        output = self.path / "draft.pdf"
        Guide(output, edition, draft=True).build()
        result = verify(output)
        self.assertEqual(result["pages"], 10)
        self.assertEqual(result["embedded_screenshots"], 0)

    def test_current_edition_builds_without_overflow(self):
        edition = read_edition(HERE / "edition.json", draft=not self.edition["final"])
        output = self.path / "edition.pdf"
        Guide(output, edition, draft=not edition["final"]).build()
        result = verify(output)
        expected = len(edition["screenshots"])
        self.assertEqual(result["embedded_screenshots"], expected)

    def test_rebuild_is_deterministic(self):
        edition = read_edition(HERE / "edition.json", draft=not self.edition["final"])
        first = self.path / "first.pdf"
        second = self.path / "second.pdf"
        for output in (first, second):
            Guide(output, edition, draft=not edition["final"]).build()
        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_checklist_can_be_filled_and_saved(self):
        edition = read_edition(HERE / "edition.json", draft=not self.edition["final"])
        original = self.path / "blank.pdf"
        completed = self.path / "completed.pdf"
        Guide(original, edition, draft=not edition["final"]).build()
        with pymupdf.open(original) as document:
            page = document[8]
            widgets = {widget.field_name: widget for widget in page.widgets()}
            first = widgets["check-1"]
            first.field_value = first.on_state()
            first.update()
            note = widgets["One thing I loved"]
            note.field_value = "The room shortcuts"
            note.update()
            document.save(completed)
        with pymupdf.open(completed) as document:
            page = document[8]
            values = {
                widget.field_name: widget.field_value for widget in page.widgets()
            }
            self.assertEqual(values["check-1"], "Yes")
            self.assertEqual(values["check-2"], "Off")
            self.assertEqual(values["One thing I loved"], "The room shortcuts")


if __name__ == "__main__":
    unittest.main()
