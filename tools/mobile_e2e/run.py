import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.pocketdisco.internal"


def visible(node):
    bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    return len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]


class Device:
    def __init__(self, adb, target, label):
        self.adb = [adb, *target]
        self.label = label

    def command(self, *args):
        result = subprocess.run(
            [*self.adb, *args], check=True, capture_output=True, text=True, timeout=35
        )
        return result.stdout

    def nodes(self):
        self.command("shell", "uiautomator", "dump", "/sdcard/pocketdisco-e2e.xml")
        raw = self.command("shell", "cat", "/sdcard/pocketdisco-e2e.xml")
        return [
            node
            for node in ET.fromstring(raw).iter("node")
            if node.get("package") == PACKAGE and visible(node)
        ]

    def scroll(self, down=True):
        dimensions = re.findall(r"(\d+)x(\d+)", self.command("shell", "wm", "size"))[-1]
        width, height = map(int, dimensions)
        top, bottom = int(height * 0.25), int(height * 0.75)
        start, end = (bottom, top) if down else (top, bottom)
        self.command(
            "shell",
            "input",
            "swipe",
            str(width // 2),
            str(start),
            str(width // 2),
            str(end),
            "400",
        )

    def find(self, test_id, scroll=False):
        for attempt in range(8 if scroll else 1):
            node = next(
                (node for node in self.nodes() if node.get("resource-id") == test_id),
                None,
            )
            if node is not None:
                return node
            if scroll:
                self.scroll(attempt < 4)
        raise AssertionError(f"{self.label}: missing {test_id}")

    def tap(self, test_id, scroll=True):
        node = self.find(test_id, scroll)
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
        self.command(
            "shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2)
        )

    def fill(self, test_id, value):
        if not re.fullmatch(r"[A-Za-z0-9 ]+", value):
            raise ValueError("Only plain test strings are supported")
        self.tap(test_id)
        self.command("shell", "input", "text", value.replace(" ", "%s"))
        self.command("shell", "input", "keyevent", "4")

    def wait_for(self, text=None, test_id=None, scroll=False):
        deadline = time.monotonic() + 25
        attempt = 0
        while time.monotonic() < deadline:
            nodes = self.nodes()
            if any(
                (test_id is not None and node.get("resource-id") == test_id)
                or (text is not None and text in node.get("text", ""))
                for node in nodes
            ):
                return
            if scroll:
                self.scroll(attempt % 8 < 4)
                attempt += 1
            time.sleep(0.4)
        raise AssertionError(f"{self.label}: expected UI did not appear")

    def launch(self):
        self.command(
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            f"{PACKAGE}/com.pocketdisco.MainActivity",
        )

    def capture(self, path):
        self.find("room-screen")
        self.command("shell", "screencap", "-p", "/sdcard/pocketdisco-e2e.png")
        self.command("pull", "/sdcard/pocketdisco-e2e.png", str(path))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--emulator", default="emulator-5554")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reset-test-session", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    phone = Device(args.adb, ["-d"], "phone")
    emulator = Device(args.adb, ["-s", args.emulator], "emulator")
    results = []

    def passed(name):
        results.append({"name": name, "result": "passed"})
        print(f"PASS {name}", flush=True)

    try:
        for device in (phone, emulator):
            if args.reset_test_session:
                device.command("shell", "pm", "clear", PACKAGE)
            device.launch()
            device.wait_for(test_id="welcome-screen")
        passed("cold launch on both devices")

        phone.fill("display-name-input", "Phone Host")
        phone.fill("room-name-input", "PocketDisco Test")
        phone.tap("create-room-button")
        phone.wait_for(test_id="room-screen")
        phone.wait_for(text="Connected")
        invite = phone.find("invite-code").get("text")
        passed("phone creates an authenticated private room")

        emulator.tap("join-tab")
        emulator.fill("display-name-input", "Emulator Friend")
        emulator.fill("invite-code-input", invite)
        emulator.tap("join-room-button")
        emulator.wait_for(test_id="room-screen")
        emulator.wait_for(text="Connected")
        phone.wait_for(text="Emulator Friend")
        passed("emulator joins and presence reaches the phone")

        phone.tap("ready-toggle-button")
        phone.wait_for(text="All settled in.")
        passed("readiness acknowledged by the server")

        phone.fill("chat-message-input", "Hello from the phone")
        phone.tap("send-message-button")
        emulator.find("chat-message-input", scroll=True)
        emulator.wait_for(text="Hello from the phone", scroll=True)
        emulator.fill("chat-message-input", "Hello from the emulator")
        emulator.tap("send-message-button")
        phone.wait_for(text="Hello from the emulator", scroll=True)
        passed("two-way durable chat")

        emulator.command("shell", "input", "keyevent", "3")
        time.sleep(1)
        emulator.launch()
        emulator.wait_for(text="Connected", scroll=True)
        passed("background and foreground snapshot recovery")

        phone.command("shell", "am", "force-stop", PACKAGE)
        phone.launch()
        phone.wait_for(test_id="room-screen")
        phone.wait_for(text="Connected")
        phone.find("chat-message-input", scroll=True)
        phone.wait_for(text="Hello from the emulator", scroll=True)
        passed("process restart restores encrypted session and chat")

        emulator.command("shell", "am", "force-stop", PACKAGE)
        emulator.command("reverse", "--remove", "tcp:8000")
        try:
            emulator.launch()
            emulator.wait_for(test_id="resume-room-button", scroll=True)
        finally:
            emulator.command("reverse", "tcp:8000", "tcp:8000")
        emulator.tap("resume-room-button")
        emulator.wait_for(test_id="room-screen")
        emulator.wait_for(text="Connected")
        passed("offline cold start recovers through saved room retry")

        for device in (phone, emulator):
            device.scroll(False)
            device.scroll(False)
            device.capture(args.output / f"{device.label}-room.png")
    except Exception as error:
        results.append({"name": "run", "result": "failed", "error": str(error)})
        raise
    finally:
        report = {"package": PACKAGE, "audio_tested": False, "checks": results}
        (args.output / "results.json").write_text(
            json.dumps(report, indent=2) + "\n", encoding="utf-8"
        )


if __name__ == "__main__":
    main()
