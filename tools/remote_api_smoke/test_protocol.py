import asyncio
import io
import json
import logging
import socket
import sqlite3
import tempfile
import threading
import time
import unittest
from contextlib import closing, contextmanager, redirect_stderr, redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
import run
import uvicorn
from pocketdisco.app import create_app
from pocketdisco.config import Settings


@contextmanager
def local_api(directory):
    database = (Path(directory) / "smoke.db").as_posix()
    app = create_app(Settings(mode="local_test", database_url=f"sqlite+aiosqlite:///{database}"))
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(16)
        port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(app, log_config=None, access_log=False))
        thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()
        try:
            deadline = time.monotonic() + 10
            while not server.started:
                if not thread.is_alive() or time.monotonic() > deadline:
                    raise RuntimeError("Local test server did not start")
                time.sleep(0.01)
            yield f"http://127.0.0.1:{port}", database
        finally:
            server.should_exit = True
            thread.join(timeout=5)
            if thread.is_alive():
                server.force_exit = True
                thread.join(timeout=3)
            if thread.is_alive():
                raise RuntimeError("Local test server did not stop")


class ProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def test_expired_ticket_rejection_is_not_counted_as_single_use_proof(self):
        probe = run.Probe(None, "https://api.example.test", run.Report())
        denial = run.InvalidStatus(SimpleNamespace(status_code=403, reason_phrase="Forbidden"))
        probe.connect = AsyncMock(side_effect=denial)
        with patch.object(run, "monotonic", return_value=20):
            with self.assertRaises(run.SmokeFailure):
                await probe.reject_spent_ticket(run.Ticket("unused", 19))
            await probe.reject_spent_ticket(run.Ticket("unused", 21))

    async def test_event_attempts_are_bounded(self):
        websocket = AsyncMock()
        websocket.recv.return_value = json.dumps({"v": 1, "type": "pong"})
        room = run.RoomSocket(websocket, "unused-room")
        with self.assertRaises(run.SmokeFailure):
            await room.until(lambda event: event["type"] == "hello")
        self.assertEqual(websocket.recv.await_count, 64)

    async def test_event_wait_has_a_deadline(self):
        websocket = AsyncMock()
        room = run.RoomSocket(websocket, "unused-room")

        async def delayed():
            await asyncio.sleep(60)

        websocket.recv.side_effect = delayed
        with patch.object(run, "WAIT_SECONDS", 0.01):
            with self.assertRaises(asyncio.TimeoutError):
                await room.until(lambda event: True)

    async def test_http_redirect_is_rejected_without_forwarding_credentials(self):
        calls = []

        def response(request):
            calls.append(request)
            return httpx.Response(307, headers={"Location": "https://other.example.test"})

        async with httpx.AsyncClient(
            transport=httpx.MockTransport(response), base_url="https://api.example.test"
        ) as client:
            probe = run.Probe(client, "https://api.example.test", run.Report())
            with self.assertRaises(run.SmokeFailure):
                await probe.request("GET", "/healthz")
        self.assertEqual(len(calls), 1)

    async def test_oversized_response_is_rejected(self):
        transport = httpx.MockTransport(
            lambda request: httpx.Response(
                200,
                content=b"x" * (run.MAX_RESPONSE + 1),
            )
        )
        async with httpx.AsyncClient(
            transport=transport, base_url="https://api.example.test"
        ) as client:
            probe = run.Probe(client, "https://api.example.test", run.Report())
            with self.assertRaises(run.SmokeFailure):
                await probe.request("GET", "/healthz")


class EndToEndTests(unittest.TestCase):
    def test_full_control_flow_and_no_secret_output(self):
        secrets = []
        original_request = run.Probe.request

        async def record_secrets(probe, *args, **kwargs):
            data = await original_request(probe, *args, **kwargs)
            if isinstance(data, dict):
                for key in ("access_token", "refresh_token", "ticket", "invite_code"):
                    if key in data:
                        secrets.append(data[key])
            return data

        output = io.StringIO()
        logs = io.StringIO()
        handler = logging.StreamHandler(logs)
        logger = logging.getLogger()
        previous_level = logger.level
        logger.addHandler(handler)
        logger.setLevel(logging.DEBUG)
        try:
            with tempfile.TemporaryDirectory(dir=run.WORKSPACE / ".local-tools") as directory:
                path = Path(directory) / "report.json"
                with local_api(directory) as (origin, database):
                    with patch.object(run.Probe, "request", record_secrets):
                        with redirect_stdout(output), redirect_stderr(output):
                            result = run.main(
                                [
                                    "--base-url",
                                    origin,
                                    "--allow-local-http",
                                    "--report",
                                    str(path),
                                ]
                            )
                    self.assertEqual(result, 0, output.getvalue())
                    report_text = path.read_text()
                    report = json.loads(report_text)
                    self.assertEqual(report["status"], "passed")
                    self.assertEqual(report["room_cleanup"], "complete")
                    self.assertEqual(len(report["checks"]), 9)
                    self.assertTrue(all(check["status"] == "passed" for check in report["checks"]))
                    with closing(sqlite3.connect(database)) as connection:
                        self.assertEqual(
                            connection.execute("SELECT COUNT(*) FROM users").fetchone()[0], 2
                        )
                        self.assertEqual(
                            connection.execute("SELECT closed FROM rooms").fetchone()[0], 1
                        )
                        self.assertEqual(
                            connection.execute("SELECT COUNT(*) FROM room_members").fetchone()[0], 0
                        )
                self.assertGreaterEqual(len(secrets), 8)
                captured = output.getvalue() + logs.getvalue() + report_text
                self.assertTrue(
                    all(secret not in captured for secret in secrets),
                    "Smoke output exposed a generated credential",
                )
        finally:
            logger.removeHandler(handler)
            logger.setLevel(previous_level)

    def test_failed_run_leaves_room_and_sanitizes_error(self):
        output = io.StringIO()
        secret = "DO_NOT_PRINT_TEST_CREDENTIAL"
        with tempfile.TemporaryDirectory(dir=run.WORKSPACE / ".local-tools") as directory:
            path = Path(directory) / "report.json"
            with local_api(directory) as (origin, database):
                with patch.object(run.Probe, "socket", side_effect=RuntimeError(secret)):
                    with redirect_stdout(output), redirect_stderr(output):
                        result = run.main(
                            [
                                "--base-url",
                                origin,
                                "--allow-local-http",
                                "--report",
                                str(path),
                            ]
                        )
                self.assertEqual(result, 1)
                report_text = path.read_text()
                self.assertNotIn(secret, output.getvalue() + report_text)
                report = json.loads(report_text)
                self.assertEqual(report["checks"][-1]["check"], "socket_presence")
                self.assertEqual(report["checks"][-1]["status"], "failed")
                self.assertEqual(report["room_cleanup"], "complete")
                with closing(sqlite3.connect(database)) as connection:
                    self.assertEqual(
                        connection.execute("SELECT closed FROM rooms").fetchone()[0], 1
                    )
                    self.assertEqual(
                        connection.execute("SELECT COUNT(*) FROM room_members").fetchone()[0], 0
                    )


if __name__ == "__main__":
    unittest.main()
