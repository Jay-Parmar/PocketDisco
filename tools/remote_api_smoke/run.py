import argparse
import asyncio
import ipaddress
import json
import logging
import re
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit
from uuid import UUID, uuid4

import httpx
from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus, WebSocketException

WORKSPACE = Path(__file__).resolve().parents[2]
CHECKS = {
    "setup",
    "health",
    "guests",
    "room_create_join",
    "socket_presence",
    "readiness",
    "two_way_chat",
    "reconnect_recovery",
    "single_use_ticket",
    "leave",
}
REASONS = {"configuration", "protocol", "network", "timeout", "unexpected", "interrupted"}
MAX_RESPONSE = 256 * 1024
WAIT_SECONDS = 15


class SmokeFailure(Exception):
    def __init__(self, reason="protocol"):
        if reason not in REASONS:
            raise ValueError("Unknown failure reason")
        self.reason = reason
        super().__init__(reason)


def require(condition):
    if not condition:
        raise SmokeFailure()


def base_url(value, allow_local_http):
    try:
        url = urlsplit(value)
        host = url.hostname or ""
        valid = (
            value == value.strip()
            and not re.search(r"[\x00-\x20\x7f\\?#@]", value)
            and re.fullmatch(r"[A-Za-z0-9.:-]+", host)
            and url.path in ("", "/")
            and url.port != 0
        )
        if not valid:
            raise ValueError
        origin = f"{url.scheme}://{url.netloc}"
        if url.scheme == "https":
            return origin
        if url.scheme == "http" and allow_local_http and ipaddress.ip_address(host).is_loopback:
            return origin
    except ValueError:
        pass
    raise SmokeFailure("configuration")


def report_path(value):
    try:
        path = (WORKSPACE / value).resolve()
    except (OSError, ValueError):
        raise SmokeFailure("configuration") from None
    output_root = (WORKSPACE / ".local-tools").resolve()
    if not output_root.is_relative_to(WORKSPACE) or not path.is_relative_to(output_root):
        raise SmokeFailure("configuration")
    if path.suffix != ".json" or path.exists():
        raise SmokeFailure("configuration")
    return path


@contextmanager
def quiet_logs():
    previous = logging.root.manager.disable
    logging.disable(logging.CRITICAL)
    try:
        yield
    finally:
        logging.disable(previous)


class Report:
    def __init__(self):
        self.current = "setup"
        self.checks = []
        self.cleanup = "not_started"

    def start(self, check):
        if check not in CHECKS:
            raise ValueError("Unknown check")
        self.current = check

    def passed(self):
        self.checks.append({"check": self.current, "status": "passed"})
        print(f"PASS {self.current}", flush=True)

    def failed(self, reason):
        if reason not in REASONS:
            raise ValueError("Unknown failure reason")
        self.checks.append({"check": self.current, "status": "failed", "reason": reason})
        print(f"FAIL {self.current}: {reason}", flush=True)

    def document(self):
        complete = self.checks and self.checks[-1] == {"check": "leave", "status": "passed"}
        failed = any(check["status"] == "failed" for check in self.checks)
        status = "passed" if complete and self.cleanup == "complete" else "incomplete"
        return {
            "scope": "room_control_only",
            "status": "failed" if failed else status,
            "checks": self.checks,
            "room_cleanup": self.cleanup,
        }

    def save(self, path):
        checked = report_path(path)
        checked.parent.mkdir(parents=True, exist_ok=True)
        with checked.open("x", encoding="utf-8") as file:
            json.dump(self.document(), file, indent=2)
            file.write("\n")


def identifier(value):
    require(isinstance(value, str))
    require(str(UUID(value)) == value)
    return value


def credential(value):
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_-]{32,128}", value))
    return value


@dataclass
class Guest:
    user_id: str
    token: str = field(repr=False)

    @property
    def headers(self):
        return {"Authorization": f"Bearer {self.token}"}


def member(snapshot, guest):
    return next(item for item in snapshot["members"] if item["user_id"] == guest.user_id)


def has_messages(snapshot, expected):
    actual = [(item["user_id"], item["body"]) for item in snapshot["messages"]]
    return actual == expected


class RoomSocket:
    def __init__(self, websocket, room_id):
        self.websocket = websocket
        self.room_id = room_id
        self.snapshot = None

    async def read(self):
        event = json.loads(await self.websocket.recv())
        require(type(event["v"]) is int and event["v"] == 1)
        require(event["type"] != "error")
        if event["type"] == "room.snapshot":
            snapshot = event["payload"]
            require(event["room_id"] == self.room_id == snapshot["room_id"])
            require(type(event["revision"]) is int and event["revision"] == snapshot["revision"])
            require(len(snapshot["members"]) <= 25 and len(snapshot["messages"]) <= 50)
            if self.snapshot is None or snapshot["revision"] >= self.snapshot["revision"]:
                self.snapshot = snapshot
        return event

    async def until(self, predicate):
        async def receive():
            for _ in range(64):
                event = await self.read()
                if predicate(event):
                    return event
            raise SmokeFailure()

        return await asyncio.wait_for(receive(), WAIT_SECONDS)

    async def wait_snapshot(self, predicate):
        if self.snapshot is None or not predicate(self.snapshot):
            await self.until(
                lambda event: event["type"] == "room.snapshot" and predicate(self.snapshot)
            )
        return self.snapshot

    async def send(self, kind, payload=None):
        command_id = str(uuid4())
        await asyncio.wait_for(
            self.websocket.send(
                json.dumps(
                    {
                        "v": 1,
                        "type": kind,
                        "command_id": command_id,
                        "payload": payload or {},
                    }
                )
            ),
            WAIT_SECONDS,
        )
        if kind in {"member.ready", "chat.send"}:
            await self.until(
                lambda event: (
                    event["type"] == "command.ack" and event["payload"]["command_id"] == command_id
                )
            )


class DirectSocket(connect):
    def process_redirect(self, error):
        return error


class Probe:
    def __init__(self, client, origin, report):
        self.client = client
        self.origin = origin
        self.report = report
        self.room_id = None
        self.room_attempted = False
        self.guests = []
        self.sockets = []

    async def request(self, method, path, guest=None, body=None, status=200):
        headers = guest.headers if guest else {}
        async with self.client.stream(method, path, headers=headers, json=body) as response:
            require(response.status_code == status)
            if status in {403, 404}:
                return None
            raw = bytearray()
            async for chunk in response.aiter_bytes(16384):
                require(len(raw) + len(chunk) <= MAX_RESPONSE)
                raw.extend(chunk)
        document = json.loads(raw)
        require(isinstance(document, dict))
        return document

    async def guest(self, name):
        data = await self.request("POST", "/v1/auth/guest", body={"display_name": name}, status=201)
        require(data["user"]["display_name"] == name)
        guest = Guest(identifier(data["user"]["id"]), credential(data["access_token"]))
        self.guests.append(guest)
        return guest

    async def ticket(self, guest):
        data = await self.request("POST", "/v1/realtime/tickets", guest, {"room_id": self.room_id})
        return credential(data["ticket"])

    def connect(self, ticket):
        scheme = "wss" if self.origin.startswith("https:") else "ws"
        origin = scheme + self.origin[self.origin.index(":") :]
        return DirectSocket(
            f"{origin}/v1/realtime?ticket={ticket}",
            open_timeout=10,
            close_timeout=2,
            max_size=MAX_RESPONSE,
            max_queue=8,
            proxy=None,
        )

    async def socket(self, ticket):
        websocket = await self.connect(ticket)
        self.sockets.append(websocket)
        socket = RoomSocket(websocket, self.room_id)
        hello = await asyncio.wait_for(socket.read(), WAIT_SECONDS)
        require(hello["type"] == "hello" and hello["payload"]["heartbeat_interval_ms"] == 15000)
        await socket.wait_snapshot(lambda snapshot: True)
        return socket

    async def exercise(self):
        report = self.report
        report.start("health")
        health = await self.request("GET", "/healthz")
        require(health["status"] == "ok")
        modes = {"production", "local_test"} if self.origin.startswith("http:") else {"production"}
        require(health["mode"] in modes)
        report.passed()

        report.start("guests")
        suffix = uuid4().hex[:8]
        host = await self.guest(f"Smoke Host {suffix}")
        friend = await self.guest(f"Smoke Guest {suffix}")
        require(host.user_id != friend.user_id and host.token != friend.token)
        report.passed()

        report.start("room_create_join")
        self.room_attempted = True
        created = await self.request(
            "POST", "/v1/rooms", host, {"name": f"Smoke test {suffix}"}, 201
        )
        self.room_id = identifier(created["snapshot"]["room_id"])
        require(created["snapshot"]["host_id"] == host.user_id)
        invite = created["invite_code"]
        require(isinstance(invite, str) and re.fullmatch(r"[0-9A-Z]{12}", invite))
        joined = await self.request("POST", f"/v1/rooms/{invite}/join", friend, {})
        require(joined["snapshot"]["room_id"] == self.room_id)
        require(
            {item["user_id"] for item in joined["snapshot"]["members"]}
            == {host.user_id, friend.user_id}
        )
        report.passed()

        report.start("socket_presence")
        used_ticket = await self.ticket(host)
        first = await self.socket(used_ticket)
        second = await self.socket(await self.ticket(friend))
        for socket in (first, second):
            await socket.wait_snapshot(
                lambda snapshot: all(
                    member(snapshot, guest)["connected"] is True for guest in (host, friend)
                )
            )
        report.passed()

        report.start("readiness")
        await first.send("member.ready", {"ready": True})
        await second.send("member.ready", {"ready": True})
        for socket in (first, second):
            await socket.wait_snapshot(
                lambda snapshot: all(
                    member(snapshot, guest)["ready"] is True for guest in (host, friend)
                )
            )
        report.passed()

        report.start("two_way_chat")
        expected = [(host.user_id, "Smoke host message"), (friend.user_id, "Smoke guest reply")]
        await first.send("chat.send", {"body": expected[0][1]})
        await second.wait_snapshot(lambda snapshot: has_messages(snapshot, expected[:1]))
        await second.send("chat.send", {"body": expected[1][1]})
        for socket in (first, second):
            await socket.wait_snapshot(lambda snapshot: has_messages(snapshot, expected))
        report.passed()

        report.start("reconnect_recovery")
        await second.websocket.close()
        await first.wait_snapshot(
            lambda snapshot: (
                member(snapshot, friend)["connected"] is False
                and member(snapshot, friend)["ready"] is False
            )
        )
        expected.append((host.user_id, "Smoke message during reconnect"))
        await first.send("chat.send", {"body": expected[-1][1]})
        second = await self.socket(await self.ticket(friend))
        recovered = await second.wait_snapshot(lambda snapshot: has_messages(snapshot, expected))
        require(member(recovered, friend)["connected"] is True)
        require(member(recovered, friend)["ready"] is False)
        await second.send("sync.request")
        await second.until(
            lambda event: (
                event["type"] == "room.snapshot" and has_messages(event["payload"], expected)
            )
        )
        durable = await self.request("GET", f"/v1/rooms/{self.room_id}/snapshot", friend)
        require(has_messages(durable, expected) and durable["revision"] >= recovered["revision"])
        report.passed()

        report.start("single_use_ticket")
        try:
            reused = await self.connect(used_ticket)
        except InvalidStatus as error:
            require(error.response.status_code == 403)
        else:
            self.sockets.append(reused)
            raise SmokeFailure()
        report.passed()

        report.start("leave")
        await self.request("POST", f"/v1/rooms/{self.room_id}/leave", friend, {})
        await first.wait_snapshot(
            lambda snapshot: (
                len(snapshot["members"]) == 1 and snapshot["members"][0]["user_id"] == host.user_id
            )
        )
        await self.request("GET", f"/v1/rooms/{self.room_id}/snapshot", friend, status=403)
        await self.request("POST", f"/v1/rooms/{self.room_id}/leave", host, {})
        await self.request("GET", f"/v1/rooms/{self.room_id}/snapshot", host, status=404)
        self.guests.clear()
        report.passed()

    async def cleanup(self):
        clean = not (self.room_attempted and self.room_id is None)
        for websocket in reversed(self.sockets):
            try:
                await asyncio.wait_for(websocket.close(), 3)
            except Exception:
                clean = False
        if self.room_id:
            for guest in reversed(self.guests):
                try:
                    async with self.client.stream(
                        "POST",
                        f"/v1/rooms/{self.room_id}/leave",
                        headers=guest.headers,
                        json={},
                        timeout=3,
                    ) as response:
                        clean = clean and response.status_code in {200, 403, 404}
                except Exception:
                    clean = False
        self.report.cleanup = "complete" if clean else "incomplete"


async def run_smoke(origin, report):
    async with httpx.AsyncClient(
        base_url=origin,
        timeout=10,
        trust_env=False,
        follow_redirects=False,
        limits=httpx.Limits(max_connections=4, max_keepalive_connections=2),
    ) as client:
        probe = Probe(client, origin, report)
        try:
            await asyncio.wait_for(probe.exercise(), 120)
        finally:
            try:
                await asyncio.wait_for(probe.cleanup(), 15)
            except Exception:
                report.cleanup = "incomplete"


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise SmokeFailure("configuration")


def main(argv=None):
    report = Report()
    parser = Parser(description="Test room control only. Creates two synthetic guest sessions.")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--allow-local-http", action="store_true")
    parser.add_argument("--report", help="New JSON file under the workspace .local-tools directory")
    try:
        args = parser.parse_args(argv)
        origin = base_url(args.base_url, args.allow_local_http)
        path = report_path(args.report) if args.report else None
    except (SmokeFailure, OSError, ValueError):
        report.failed("configuration")
        return 2
    except SystemExit as error:
        return error.code
    code = 0
    try:
        with quiet_logs():
            asyncio.run(run_smoke(origin, report))
    except SmokeFailure as error:
        report.failed(error.reason)
        code = 1
    except (asyncio.TimeoutError, httpx.TimeoutException):
        report.failed("timeout")
        code = 1
    except (httpx.HTTPError, WebSocketException, OSError):
        report.failed("network")
        code = 1
    except KeyboardInterrupt:
        report.failed("interrupted")
        code = 130
    except Exception:
        report.failed("unexpected")
        code = 1
    if report.cleanup != "complete":
        print("Room cleanup incomplete; inspect synthetic test records.", flush=True)
        code = code or 1
    if path:
        try:
            report.save(path)
        except (OSError, SmokeFailure):
            print("Report could not be saved.", flush=True)
            code = code or 1
    print("Room control only; playback and audio synchronization were not tested.", flush=True)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
