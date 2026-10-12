import argparse
import json
import re
import sys
import time
from contextlib import ExitStack, contextmanager
from http.client import HTTPException
from pathlib import Path
from urllib.request import HTTPRedirectHandler, ProxyHandler, build_opener

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from tools.capture_relay_e2e.run import LAUNCHERS, bounds, system_bar_viewport, utc_now
from tools.mobile_e2e.nearby import PRODUCT, ProductDevice, local_api_address
from tools.mobile_e2e.run import port_number

ROOT = Path(__file__).resolve().parents[2]
NAMES = ("Room Host", "Room Friend A", "Room Friend B")
ROOM_NAME = "PocketDisco Three Device Test"
HINTS = {
    "display-name-input": "What should we call you?",
    "room-name-input": "Friday night feels",
    "invite-code-input": "12-character room code",
    "chat-message-input": "Say something good...",
}


class RoomFailure(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *unused):
        raise RoomFailure("backend_redirect_refused")


def health(port):
    try:
        opener = build_opener(ProxyHandler({}), NoRedirect())
        with opener.open(f"http://127.0.0.1:{port}/healthz", timeout=5) as response:
            payload = response.read(4097)
            if response.status != 200 or len(payload) > 4096:
                raise RoomFailure("backend_health_failed")
            data = json.loads(payload)
        if (
            not isinstance(data, dict)
            or data.get("status") != "ok"
            or data.get("mode") != "production"
        ):
            raise RoomFailure("backend_not_production")
    except RoomFailure:
        raise
    except (OSError, ValueError, HTTPException):
        raise RoomFailure("backend_health_failed") from None


class ApiMapping:
    def __init__(self, device, host_port):
        self.device, self.target = device, f"tcp:{host_port}"
        self.created = self.suspended = False

    def current(self):
        matches = []
        socket = r"(?:tcp:\d{1,5}|local(?:abstract|reserved|filesystem):\S+)"
        for line in self.device.command("reverse", "--list").splitlines():
            row = line.split()
            if len(row) != 3 or not all(
                re.fullmatch(socket, value) for value in row[1:]
            ):
                raise RoomFailure("unrecognized_api_mapping")
            if row[1] == "tcp:8000":
                matches.append(row[2])
        if len(matches) > 1:
            raise RoomFailure("ambiguous_api_mapping")
        return matches[0] if matches else None

    def __enter__(self):
        current = self.current()
        if current is not None and current != self.target:
            raise RoomFailure("conflicting_api_mapping")
        if current is None:
            self.device.command("reverse", "--no-rebind", "tcp:8000", self.target)
            self.created = True
        return self

    def restore(self):
        current = self.current()
        if current is None:
            self.device.command("reverse", "--no-rebind", "tcp:8000", self.target)
        elif current != self.target:
            raise RoomFailure("api_mapping_changed")
        self.suspended = False

    @contextmanager
    def offline(self):
        if self.suspended or self.current() != self.target:
            raise RoomFailure("api_mapping_changed")
        self.device.command("reverse", "--remove", "tcp:8000")
        self.suspended = True
        try:
            yield
        finally:
            self.restore()

    def __exit__(self, *unused):
        if self.suspended:
            self.restore()
        if self.created:
            current = self.current()
            if current is not None and current != self.target:
                raise RoomFailure("api_mapping_changed")
            if current == self.target:
                self.device.command("reverse", "--remove", "tcp:8000")


def field_empty(node, identity):
    return node.get("text", "") in {"", node.get("hint"), HINTS.get(identity)}


def member_present(nodes, name, statuses):
    return any(
        node.get("resource-id", "").startswith("member-")
        and (parts := node.get("content-desc", "").split(", "))[0] == name
        and parts[-1] in statuses
        for node in nodes
    )


def message_present(nodes, message):
    return any(
        node.get("resource-id", "").startswith("message-")
        and node.get("content-desc", "").endswith(": " + message)
        for node in nodes
    )


class RoomDevice(ProductDevice):
    def __init__(self, adb, serial, label):
        super().__init__(adb, serial, label)
        self.fresh_check = False
        self.deadline = time.monotonic() + 900
        self.last_nodes = []

    def launch_product(self):
        self.check_foreground({PRODUCT, *LAUNCHERS})
        super().launch_product()

    def wait_ui(self, predicate, scroll=False):
        deadline = min(time.monotonic() + 30, self.deadline)
        for attempt in range(12):
            if time.monotonic() >= deadline:
                break
            nodes = self.nodes({PRODUCT})
            self.last_nodes = nodes
            if self.fresh_check and any(
                node.get("resource-id") in {"resume-room-button", "room-screen"}
                for node in nodes
            ):
                raise RoomFailure("saved_room_present")
            result = predicate(nodes)
            if result is not None and result is not False:
                return result
            if scroll:
                self.check_foreground({PRODUCT})
                self.scroll(attempt % 8 < 4)
            time.sleep(0.25)
        raise RoomFailure("expected_ui_state_missing")

    def find_id(self, identity):
        return self.wait_ui(
            lambda nodes: next(
                (node for node in nodes if node.get("resource-id") == identity), None
            ),
            scroll=True,
        )

    def tap_id(self, identity):
        for attempt in range(8):
            node = self.find_id(identity)
            if node.get("enabled") != "true":
                time.sleep(0.25)
                continue
            left_edge, top_edge, right_edge, bottom_edge = system_bar_viewport(
                self.command("shell", "dumpsys", "window", "displays")
            )
            for parent in self.last_nodes:
                if parent.get("class") == "android.widget.ScrollView" and any(
                    child is node for child in parent.iter()
                ):
                    left, top, right, bottom = bounds(parent)
                    left_edge, top_edge = max(left_edge, left), max(top_edge, top)
                    right_edge, bottom_edge = (
                        min(right_edge, right),
                        min(bottom_edge, bottom),
                    )
            left, top, right, bottom = bounds(node)
            if not left_edge <= left < right <= right_edge:
                raise RoomFailure("control_outside_viewport")
            if top_edge <= top < bottom <= bottom_edge:
                self.tap_node(node)
                return
            if attempt < 7:
                self.check_foreground({PRODUCT})
                self.scroll(down=top >= top_edge, small=True)
        raise RoomFailure("control_not_safely_tappable")

    def fill_id(self, identity, value):
        if not re.fullmatch(r"[A-Za-z0-9 ]+", value):
            raise RoomFailure("invalid_test_input")
        node = self.find_id(identity)
        if node.get("text") == value:
            return
        if not field_empty(node, identity):
            raise RoomFailure("existing_input_preserved")
        self.tap_id(identity)
        if not field_empty(self.find_id(identity), identity):
            raise RoomFailure("existing_input_preserved")
        self.check_foreground({PRODUCT})
        self.command("shell", "input", "text", value.replace(" ", "%s"))
        self.check_foreground({PRODUCT})
        self.command("shell", "input", "keyevent", "4")

    def verify_welcome(self, name):
        self.fresh_check = True
        try:
            self.find_id("welcome-screen")
            self.tap_id("server-settings-toggle")
            if not local_api_address(self.find_id("server-url-input")):
                raise RoomFailure("saved_server_preserved")
            self.tap_id("server-settings-toggle")
            for identity, expected in (
                ("display-name-input", name),
                ("room-name-input", ROOM_NAME),
            ):
                self.tap_id("create-tab")
                node = self.find_id(identity)
                if not field_empty(node, identity) and node.get("text") != expected:
                    raise RoomFailure("existing_input_preserved")
            self.tap_id("join-tab")
            if not field_empty(self.find_id("invite-code-input"), "invite-code-input"):
                raise RoomFailure("existing_input_preserved")
            self.tap_id("create-tab")
        finally:
            self.fresh_check = False

    def connected(self):
        self.find_id("room-screen")
        self.wait_ui(
            lambda nodes: any(node.get("text") == "Connected" for node in nodes),
            scroll=True,
        )

    def members(self, statuses=None):
        expected = statuses or {"Here", "Ready"}
        self.wait_ui(
            lambda nodes: all(member_present(nodes, name, expected) for name in NAMES),
            scroll=True,
        )

    def message(self, body):
        self.wait_ui(lambda nodes: message_present(nodes, body), scroll=True)

    def send(self, body):
        self.fill_id("chat-message-input", body)
        self.tap_id("send-message-button")
        self.wait_ui(
            lambda nodes: any(
                node.get("resource-id") == "chat-message-input"
                and field_empty(node, "chat-message-input")
                for node in nodes
            )
        )
        self.message(body)

    def stop_product(self):
        self.check_foreground({PRODUCT})
        self.command("shell", "am", "force-stop", PRODUCT)

    def leave(self):
        self.tap_id("leave-room-button")

        def confirmation(nodes):
            texts = {node.get("text") for node in nodes}
            if not {"Leave this room?", "You can return with the room code."} <= texts:
                return None
            return next(
                (
                    node
                    for node in nodes
                    if node.get("resource-id") == "android:id/button1"
                    and node.get("text", "").upper() == "LEAVE ROOM"
                    and node.get("enabled") == "true"
                ),
                None,
            )

        button = self.wait_ui(confirmation)
        left, top, right, bottom = bounds(button)
        left_edge, top_edge, right_edge, bottom_edge = system_bar_viewport(
            self.command("shell", "dumpsys", "window", "displays")
        )
        if not (
            left_edge <= left < right <= right_edge
            and top_edge <= top < bottom <= bottom_edge
        ):
            raise RoomFailure("leave_confirmation_not_visible")
        self.tap_node(button)
        self.find_id("welcome-screen")


def exercise(devices, mappings, passed):
    host, friend, retry = devices
    host.fill_id("display-name-input", NAMES[0])
    host.fill_id("room-name-input", ROOM_NAME)
    host.tap_id("create-room-button")
    host.connected()
    invite = host.find_id("invite-code").get("text", "")
    if not re.fullmatch(r"[0-9A-HJKMNP-TV-Z]{12}", invite):
        raise RoomFailure("unexpected_test_invite")
    passed("host creates a private room")
    for device, name in zip(devices[1:], NAMES[1:], strict=True):
        device.tap_id("join-tab")
        device.fill_id("display-name-input", name)
        device.fill_id("invite-code-input", invite)
        device.tap_id("join-room-button")
        device.connected()
    for device in devices:
        device.members()
    passed("three devices join and see connected members")
    for device in devices:
        device.tap_id("ready-toggle-button")
    for device in devices:
        device.members({"Ready"})
    passed("readiness reaches all three devices")
    messages = ("Hello from the host", "Hello from friend A", "Hello from friend B")
    for device, message in zip(devices, messages, strict=True):
        device.send(message)
    for device in devices:
        for message in messages:
            device.message(message)
    passed("each device sends and receives chat")
    friend.check_foreground({PRODUCT})
    friend.command("shell", "input", "keyevent", "3")
    host.wait_ui(lambda nodes: member_present(nodes, NAMES[1], {"Away"}), scroll=True)
    background_message = "Message while a friend was away"
    host.send(background_message)
    friend.launch_product()
    friend.connected()
    friend.message(background_message)
    for device in devices:
        device.members()
    passed("background return restores presence and missed chat")
    host.stop_product()
    host.launch_product()
    host.connected()
    host.message(messages[2])
    for device in devices:
        device.members()
    passed("host process restart restores the saved room")
    retry.stop_product()
    with mappings[2].offline():
        retry.launch_product()
        retry.find_id("resume-room-button")
    retry.tap_id("resume-room-button")
    retry.connected()
    retry.message(background_message)
    for device in devices:
        device.members()
    passed("offline cold start retries after the API mapping returns")
    for device in reversed(devices):
        device.leave()
    passed("test members leave through the UI")


def parse_args(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--device", action="append", required=True)
    parser.add_argument("--host-port", type=port_number, default=18080)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if (
        len(args.device) != 3
        or len(set(args.device)) != 3
        or any(not value.strip() for value in args.device)
    ):
        parser.error("Use exactly three distinct explicit devices, with the host first")
    return args


def validate_output(path):
    output = (ROOT / path).resolve()
    scratch = (ROOT / ".local-tools").resolve()
    if (
        output == scratch
        or not output.is_relative_to(ROOT)
        or not output.is_relative_to(scratch)
        or output.exists()
    ):
        raise RoomFailure("use_new_local_tools_output")
    return output


def prepare_output(path):
    output = validate_output(path)
    output.mkdir(parents=True, exist_ok=False)
    return output


def write_result(output, report):
    with (output / "results.json").open("x", encoding="utf-8") as destination:
        json.dump(report, destination, indent=2)
        destination.write("\n")


def main():
    args = parse_args()
    try:
        output = prepare_output(args.output)
    except (RoomFailure, OSError, ValueError):
        print("FAIL use_new_local_tools_output", flush=True)
        return 2
    checks = []
    report = {
        "startedAtUtc": utc_now(),
        "deviceCount": 3,
        "package": PRODUCT,
        "transport": "ADB loopback through the configured SSH tunnel",
        "hostPort": args.host_port,
        "backend": "not verified",
        "audioTested": False,
        "publicNetworkTested": False,
        "checks": checks,
    }

    def passed(name):
        checks.append({"name": name, "result": "passed", "atUtc": utc_now()})
        print(f"PASS {name}", flush=True)

    try:
        devices = [
            RoomDevice(args.adb, serial, f"device {index + 1}")
            for index, serial in enumerate(args.device)
        ]
        health(args.host_port)
        report["backend"] = "production"
        passed("production backend is healthy through the tunnel")
        for device in devices:
            device.check_foreground({PRODUCT, *LAUNCHERS})
        with ExitStack() as stack:
            mappings = [
                stack.enter_context(ApiMapping(device, args.host_port))
                for device in devices
            ]
            for device, name in zip(devices, NAMES, strict=True):
                device.launch_product()
                device.verify_welcome(name)
            passed("three empty welcome screens use the expected device API address")
            exercise(devices, mappings, passed)
    except (Exception, KeyboardInterrupt) as error:  # noqa: BLE001
        reason = str(error) if isinstance(error, RoomFailure) else "device_check_failed"
        checks.append(
            {"name": "run", "result": "failed", "reason": reason, "atUtc": utc_now()}
        )
        print(f"FAIL {reason}", flush=True)
    report["finishedAtUtc"] = utc_now()
    try:
        write_result(output, report)
    except OSError:
        print("FAIL result_write_failed", flush=True)
        return 1
    return int(any(check["result"] == "failed" for check in checks))


if __name__ == "__main__":
    raise SystemExit(main())
