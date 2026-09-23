from __future__ import annotations

import io
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock, patch

from tools.phase0_coordinator.cli import DEFAULT_BIND, DEFAULT_PORT, build_parser, main


class CoordinatorCliTests(unittest.TestCase):
    def test_default_binding_is_loopback(self) -> None:
        args = build_parser().parse_args([])

        self.assertEqual(DEFAULT_BIND, args.bind)
        self.assertEqual("127.0.0.1", args.bind)
        self.assertEqual(DEFAULT_PORT, args.port)

    def test_lan_binding_requires_explicit_argument(self) -> None:
        args = build_parser().parse_args(["--bind", "0.0.0.0", "--port", "9000"])

        self.assertEqual("0.0.0.0", args.bind)
        self.assertEqual(9000, args.port)

    def test_startup_prints_a_fresh_token(self) -> None:
        server = Mock()
        server.server_address = ("127.0.0.1", DEFAULT_PORT)
        output = io.StringIO()
        with (
            patch("tools.phase0_coordinator.cli.secrets.token_urlsafe", return_value="fresh-token") as token_urlsafe,
            patch("tools.phase0_coordinator.cli.create_server", return_value=server) as create_server,
            patch("tools.phase0_coordinator.cli.logging.basicConfig"),
            redirect_stdout(output),
        ):
            result = main([])

        self.assertEqual(0, result)
        token_urlsafe.assert_called_once_with(32)
        create_server.assert_called_once_with(DEFAULT_BIND, DEFAULT_PORT, "fresh-token")
        server.serve_forever.assert_called_once_with()
        server.server_close.assert_called_once_with()
        self.assertIn("Bearer token: fresh-token", output.getvalue())


if __name__ == "__main__":
    unittest.main()
