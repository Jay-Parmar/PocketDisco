import io
import json
import logging
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

import run


class SafetyTests(unittest.TestCase):
    def test_https_origin(self):
        self.assertEqual(
            run.base_url("https://api.example.test/", False), "https://api.example.test"
        )
        self.assertEqual(run.base_url("https://[::1]:8443", False), "https://[::1]:8443")
        self.assertEqual(
            run.base_url("HTTPS://api.example.test/", False), "https://api.example.test"
        )
        self.assertEqual(run.base_url("HTTP://127.0.0.1:8000/", True), "http://127.0.0.1:8000")

    def test_http_needs_explicit_loopback_flag(self):
        for value in ("http://127.0.0.1:8000", "http://[::1]:8000"):
            with self.subTest(value=value):
                with self.assertRaises(run.SmokeFailure):
                    run.base_url(value, False)
                self.assertEqual(run.base_url(value, True), value)
        for value in ("http://api.example.test", "http://100.88.1.2", "http://localhost"):
            with self.subTest(value=value), self.assertRaises(run.SmokeFailure):
                run.base_url(value, True)

    def test_urls_cannot_carry_credentials_or_paths(self):
        secret = "DO_NOT_PRINT_TEST_CREDENTIAL"
        values = (
            f"https://user:{secret}@example.test",
            f"https://example.test?ticket={secret}",
            f"https://example.test/#{secret}",
            f"https://example.test/{secret}",
            "https://example.test:invalid",
            "https://example.test\\other",
            "https://example.test\n",
        )
        for value in values:
            with self.subTest(value=value), self.assertRaises(run.SmokeFailure) as failure:
                run.base_url(value, False)
            self.assertNotIn(secret, str(failure.exception))

    def test_socket_redirects_are_not_followed(self):
        error = RuntimeError("DO_NOT_FORWARD_TICKET")
        connector = object.__new__(run.DirectSocket)
        self.assertIs(connector.process_redirect(error), error)

    def test_report_path_stays_in_local_workspace_outputs(self):
        expected = run.WORKSPACE / ".local-tools" / "remote-api-smoke" / "result.json"
        self.assertEqual(run.report_path(".local-tools/remote-api-smoke/result.json"), expected)
        for value in ("README.md", "../result.json", ".local-tools/../../result.json"):
            with self.subTest(value=value), self.assertRaises(run.SmokeFailure):
                run.report_path(value)

    def test_report_contains_only_fixed_checks(self):
        output = io.StringIO()
        with redirect_stdout(output):
            report = run.Report()
            report.start("health")
            report.passed()
            report.start("guests")
            report.failed("network")
        data = report.document()
        self.assertEqual(data["scope"], "room_control_only")
        self.assertEqual(data["status"], "failed")
        self.assertEqual(
            data["checks"],
            [
                {"check": "health", "status": "passed"},
                {"check": "guests", "status": "failed", "reason": "network"},
            ],
        )
        self.assertEqual(output.getvalue(), "PASS health\nFAIL guests: network\n")
        with self.assertRaises(ValueError):
            report.start("access_token=DO_NOT_PRINT")
        with self.assertRaises(ValueError):
            report.failed("ticket=DO_NOT_PRINT")

    def test_report_never_overwrites_existing_file(self):
        with tempfile.TemporaryDirectory(dir=run.WORKSPACE / ".local-tools") as directory:
            path = Path(directory) / "report.json"
            report = run.Report()
            report.save(path)
            self.assertEqual(json.loads(path.read_text())["scope"], "room_control_only")
            with self.assertRaises(run.SmokeFailure):
                report.save(path)

    def test_cli_never_prints_argument_values(self):
        secret = "DO_NOT_PRINT_TEST_CREDENTIAL"
        for args in (
            ["--base-url", f"https://user:{secret}@example.test"],
            ["--access-token", secret],
            [
                "--base-url",
                "https://api.example.test",
                "--report",
                f".local-tools/{secret}\x00.json",
            ],
        ):
            output = io.StringIO()
            with redirect_stdout(output), redirect_stderr(output):
                self.assertEqual(run.main(args), 2)
            self.assertNotIn(secret, output.getvalue())

    def test_network_logging_is_suppressed_and_restored(self):
        previous = logging.root.manager.disable
        with run.quiet_logs():
            self.assertEqual(logging.root.manager.disable, logging.CRITICAL)
        self.assertEqual(logging.root.manager.disable, previous)

    def test_cli_drops_raw_exception_details(self):
        secret = "DO_NOT_PRINT_TEST_CREDENTIAL"
        output = io.StringIO()
        with patch.object(run, "run_smoke", side_effect=RuntimeError(secret)):
            with redirect_stdout(output), redirect_stderr(output):
                self.assertEqual(run.main(["--base-url", "https://api.example.test"]), 1)
        self.assertNotIn(secret, output.getvalue())
        self.assertIn("unexpected", output.getvalue())

    def test_guest_repr_omits_token(self):
        guest = run.Guest("synthetic-user", "DO_NOT_PRINT_TEST_CREDENTIAL")
        self.assertNotIn(guest.token, repr(guest))


if __name__ == "__main__":
    unittest.main()
