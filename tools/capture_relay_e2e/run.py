import argparse
import json
import re
import secrets
import subprocess
import time
import traceback
import xml.etree.ElementTree as ET
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path

LAB = "com.pocketdisco.capturelab"
SOURCE = LAB + ".source"
MUSIC = "app.revanced.android.apps.youtube.music"
PORT = "tcp:18990"
SYSTEM = {"com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller"}
LAUNCHERS = {"com.android.launcher3", "com.google.android.apps.nexuslauncher", "com.nothing.launcher", "com.sec.android.app.launcher", "com.mi.android.globallauncher"}


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def passed_check(name, phone, emulator):
    return {
        "name": name,
        "result": "passed",
        "recordedAtUtc": utc_now(),
        "latestObservations": {"phone": deepcopy(phone.latest), "emulator": deepcopy(emulator.latest)},
    }


def names_lab(nodes):
    return any(re.search(r"\bPocketDisco Capture L(?:ab\b|\u2026|\.\.\.)", node.get("text", "")) for node in nodes)


def bounds(node):
    values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    return values if len(values) == 4 and values[2] > values[0] and values[3] > values[1] else None


def system_bar_viewport(raw):
    invalid = "Cannot verify the display's system-bar viewport"
    frames = re.findall(r"\bmDisplayFrame=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)", raw)
    if len(frames) != 1 or len(re.findall(r"\bmDisplayFrame\b", raw)) != 1:
        raise RuntimeError(invalid)
    left, top, width, height = map(int, frames[0])
    if left != 0 or top != 0 or width <= 0 or height <= 0:
        raise RuntimeError(invalid)
    lines = [line for line in raw.splitlines() if re.match(r"^\s*InsetsSource id=", line)
             and re.search(r"\btype=(?:statusBars|navigationBars)\b", line)]
    if len(lines) != 2:
        raise RuntimeError(invalid)
    viewport = [0, 0, width, height]
    kinds, identifiers, edges, rectangles = set(), set(), set(), []
    pattern = (r"^\s*InsetsSource id=(\S+) type=(statusBars|navigationBars) "
               r"frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\] visible=true(?:\s|$)")
    for line in lines:
        match = re.match(pattern, line)
        if match is None:
            raise RuntimeError(invalid)
        identifier, kind, *coordinates = match.groups()
        if kind in kinds or identifier in identifiers:
            raise RuntimeError(invalid)
        kinds.add(kind)
        identifiers.add(identifier)
        left, top, right, bottom = map(int, coordinates)
        if not (0 <= left < right <= width and 0 <= top < bottom <= height):
            raise RuntimeError(invalid)
        if left == 0 and right == width and top == 0 and bottom < height:
            edge, limit = 1, bottom
        elif left == 0 and right == width and bottom == height and top > 0:
            edge, limit = 3, top
        elif top == 0 and bottom == height and left == 0 and right < width:
            edge, limit = 0, right
        elif top == 0 and bottom == height and right == width and left > 0:
            edge, limit = 2, left
        else:
            raise RuntimeError(invalid)
        if edge in edges:
            raise RuntimeError(invalid)
        if any(max(left, other[0]) < min(right, other[2]) and
               max(top, other[1]) < min(bottom, other[3]) for other in rectangles):
            raise RuntimeError(invalid)
        edges.add(edge)
        rectangles.append((left, top, right, bottom))
        viewport[edge] = limit
    if viewport[0] >= viewport[2] or viewport[1] >= viewport[3]:
        raise RuntimeError(invalid)
    return tuple(viewport)


class Device:
    def __init__(self, adb, serial, label):
        self.adb, self.serial, self.label = adb, serial, label
        self.latest = {}

    def command(self, *args):
        try:
            result = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, text=True, encoding="utf-8", timeout=35)
        except (subprocess.TimeoutExpired, OSError):
            raise RuntimeError(f"{self.label}: ADB unavailable or timed out") from None
        if result.returncode:
            raise RuntimeError(f"{self.label}: ADB {args[0]} failed")
        return result.stdout

    def foreground(self):
        raw = self.command("shell", "dumpsys", "window")
        match = re.search(r"mCurrentFocus=Window\{(\w+)\s+u\d+\s+([^\n}]+)\}", raw)
        if not match:
            return ""
        component = re.match(r"([\w.]+)/", match.group(2))
        if component:
            return component.group(1)
        windows = self.command("shell", "dumpsys", "window", "windows")
        marker = r"^\s*Window #\d+ Window\{" + re.escape(match.group(1)) + r"\s+[^\n]+:\s*\n"
        block = re.search(marker + r"(.*?)(?=^\s*Window #\d+ |\Z)", windows, re.MULTILINE | re.DOTALL)
        owner = re.search(r"\bmOwnerUid=\d+[^\n]*\bpackage=([\w.]+)", block.group(1)) if block else None
        return owner.group(1) if owner else ""

    def check_foreground(self, allowed):
        for attempt in range(10):
            focused = self.foreground()
            if focused in allowed:
                return
            if focused or attempt == 9:
                break
            time.sleep(.1)
        known = {LAB, SOURCE, MUSIC, *SYSTEM, *LAUNCHERS}
        focus_label = focused if focused in known else "another app" if focused else "none"
        raise AssertionError(f"{self.label}: unexpected foreground or locked screen (focus: {focus_label}); no interaction attempted")

    def launch(self, package=LAB):
        activity = "SourceActivity" if package == SOURCE else "MainActivity"
        self.command("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
                     "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000", "-n", f"{package}/.{activity}")
        self.wait_foreground(package, {LAB, SOURCE, *LAUNCHERS})

    def wait_foreground(self, package, transitioning=()):
        for attempt in range(25):
            focused = self.foreground()
            if focused == package:
                return
            if focused and focused not in transitioning:
                raise AssertionError(f"{self.label}: unrelated app interrupted the test")
            time.sleep(.1)
        raise AssertionError(f"{self.label}: expected app did not finish opening")

    def nodes(self, allowed=None):
        allowed = set(allowed or {LAB})
        self.check_foreground(allowed)
        path = "/data/local/tmp/pocketdisco-capture-relay-ui.xml"
        try:
            self.command("shell", "uiautomator", "dump", path)
            raw = self.command("shell", "cat", path)
            return [n for n in ET.fromstring(raw).iter("node") if n.get("package") in allowed and bounds(n)]
        finally:
            self.command("shell", "rm", "-f", path)

    def tap_node(self, node):
        if node.get("package"):
            self.check_foreground({node.get("package")})
        left, top, right, bottom = bounds(node)
        self.command("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def scroll(self, down=True, small=False):
        width, height = map(int, re.findall(r"(\d+)x(\d+)", self.command("shell", "wm", "size"))[-1])
        start, end, duration = (.6, .4, "700") if small else (.75, .25, "350")
        if not down:
            start, end = end, start
        self.command("shell", "input", "swipe", str(width // 2), str(int(height * start)),
                     str(width // 2), str(int(height * end)), duration)

    def find(self, text, package=LAB, description=False):
        for attempt in range(8):
            node = next((n for n in self.nodes({package}) if n.get("content-desc" if description else "text") == text), None)
            if node is not None:
                return node
            self.scroll(attempt < 4)
        raise AssertionError(f"{self.label}: expected test control missing: {text}")

    def tap(self, text, package=LAB):
        for attempt in range(8):
            left_edge, top_edge, right_edge, bottom_edge = system_bar_viewport(
                self.command("shell", "dumpsys", "window", "displays"))
            node = self.find(text, package)
            left, top, right, bottom = bounds(node)
            if not left_edge <= left < right <= right_edge:
                raise AssertionError(f"{self.label}: control is outside the horizontal viewport: {text}")
            if top_edge <= top < bottom <= bottom_edge:
                self.tap_node(node)
                return
            if attempt < 7:
                self.check_foreground({package})
                self.scroll(down=top >= top_edge, small=True)
        raise AssertionError(f"{self.label}: control stayed near the screen edge: {text}")

    def fill_code(self, code):
        if not re.fullmatch(r"[0-9a-fA-F]{32}", code):
            raise ValueError("Session code must contain 32 hexadecimal characters")
        node = self.find("Session code", description=True)
        if not re.fullmatch(r"[0-9a-fA-F]{32}", node.get("text", "")):
            raise AssertionError(f"{self.label}: reset the session code in the app first")
        self.tap_node(node)
        self.command("shell", "input", "keyevent", "123", *(["67"] * 32))
        self.command("shell", "input", "text", code)
        self.command("shell", "input", "keyevent", "4")

    def consent(self, accept=True):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            nodes = self.nodes({LAB, *SYSTEM})
            permission = [n for n in nodes if n.get("package", "").endswith("permissioncontroller")]
            if permission:
                if not names_lab(permission):
                    raise AssertionError("Permission prompt does not identify the lab")
                choices = ("permission_allow_foreground_only_button", "permission_allow_button")
                node = next((n for n in permission if n.get("resource-id", "").split("/")[-1] in choices), None)
            else:
                titles = {"Start now", "Start recording", "Share screen", "Share", "Start"} if accept else {"Cancel"}
                node = next((n for n in nodes if n.get("package") == "com.android.systemui" and n.get("text") in titles and n.get("enabled") == "true"), None)
                if node is not None:
                    if not names_lab([n for n in nodes if n.get("package") == "com.android.systemui"]):
                        raise AssertionError("Projection prompt does not identify the lab")
                    self.tap_node(node)
                    self.wait_foreground(LAB, SYSTEM)
                    return
            if node is not None:
                self.tap_node(node)
            time.sleep(.3)
        raise AssertionError(f"{self.label}: projection consent did not appear")

    def dismiss_projection(self):
        if self.foreground() != "com.android.systemui":
            return
        nodes = self.nodes({"com.android.systemui"})
        if names_lab(nodes):
            cancel = next((n for n in nodes if n.get("text") == "Cancel" and n.get("enabled") == "true"), None)
            if cancel is not None:
                self.tap_node(cancel)

    def report(self):
        raw = self.command("shell", "run-as", LAB, "cat", "files/report.json")
        report = json.loads(raw)
        keys = ("diagnostic", "active", "status", "capture", "relay", "output", "sentPcm", "receivedPcm",
                "synchronizedPlayback", "acousticSyncVerified", "sourceOutputSynchronized", "playoutDelayMillis")
        keys += ("reportSequence", "observedAtNanos")
        self.latest = {key: report[key] for key in keys if key in report}
        return self.latest

    def wait_report(self, predicate, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                report = self.report()
                if predicate(report):
                    return report
            except (RuntimeError, json.JSONDecodeError):
                pass
            time.sleep(.5)
        raise AssertionError(f"{self.label}: expected diagnostic counters did not arrive")


class Forward:
    def __init__(self, phone):
        self.phone, self.created = phone, False

    def entries(self):
        return [line.split() for line in self.phone.command("forward", "--list").splitlines()]

    def __enter__(self):
        self.baseline = self.entries()
        if any(len(row) == 3 and row[1] == PORT for row in self.baseline):
            raise RuntimeError("Port 18990 already has an ADB mapping; refusing to replace it")
        self.phone.command("forward", "--no-rebind", PORT, PORT)
        self.created = True
        return self

    def __exit__(self, *unused):
        if self.created and [self.phone.serial, PORT, PORT] in self.entries():
            self.phone.command("forward", "--remove", PORT)


def audio_proof(phone, emulator):
    host = phone.wait_report(lambda r: r["capture"]["nonSilentFrames"] > 0 and bool(r["sentPcm"]["first100AudibleFramesSha256"]))
    peer = emulator.wait_report(lambda r: r["receivedPcm"]["nonSilentFrames"] > 0 and bool(r["receivedPcm"]["first100AudibleFramesSha256"]) and r["output"]["playbackHeadFrames"] > 0)
    if host["sentPcm"]["first100AudibleFramesSha256"] != peer["receivedPcm"]["first100AudibleFramesSha256"]:
        raise AssertionError("Audible PCM prefix hashes differ")
    baseline = peer["output"]["playbackHeadFrames"]
    emulator.wait_report(lambda r: r["output"]["playbackHeadFrames"] > baseline)


def silence_proof(phone, require_zero=False):
    time.sleep(2)
    before = phone.report()["capture"]
    time.sleep(4)
    after = phone.report()["capture"]
    if (not before["running"] or not after["running"] or after["frames"] <= before["frames"]
            or after["nonSilentFrames"] != before["nonSilentFrames"]
            or (require_zero and after["nonSilentFrames"] != 0)):
        raise AssertionError("Excluded-source capture was not silent while capture remained active")
    return after


def exercise(phone, emulator, passed, music_file=False):
    code = secrets.token_hex(16)
    for device in (phone, emulator):
        device.launch()
        device.fill_code(code)
    phone.tap("Host USB/TCP test")
    emulator.tap("Receive USB/TCP test")
    for device in (phone, emulator):
        device.wait_report(lambda r: r["relay"]["peers"] == 1)
    passed("authenticated USB/TCP connection")
    if music_file:
        phone.tap("Music app local test file")
    phone.tap("Start capture")
    phone.consent(False)
    phone.wait_report(lambda r: not r["capture"]["running"] and r["capture"]["frames"] == 0)
    passed("projection cancellation captures no audio")
    phone.tap("Start capture")
    phone.consent(True)
    phone.wait_report(lambda r: r["capture"]["running"] and r["capture"]["frames"] > 0)
    phone.tap("Open generated source")
    phone.wait_foreground(SOURCE, {LAB})
    phone.tap("Play allowed", SOURCE)
    if music_file:
        phone.tap("Refresh status", SOURCE)
        phone.find("Playing generated tone: capture allowed", SOURCE)
        silence_proof(phone, require_zero=True)
        passed("selected app excludes the other source UID")
        phone.tap("Open generated file in music app", SOURCE)
        phone.wait_foreground(MUSIC, {SOURCE})
    audio_proof(phone, emulator)
    passed("generated file capture and matching audible PCM" if music_file else "allowed capture and matching audible PCM")
    if music_file:
        phone.command("shell", "input", "keyevent", "4")
        phone.wait_foreground(SOURCE, {MUSIC})
    else:
        phone.tap("Play blocked", SOURCE)
        after = silence_proof(phone)
        passed("source capture policy respected")
        phone.tap("Play allowed", SOURCE)
        phone.wait_report(lambda r: r["capture"]["nonSilentFrames"] > after["nonSilentFrames"])
        emulator.command("shell", "input", "keyevent", "3")
        phone.wait_report(lambda r: r["relay"]["peers"] == 0)
        emulator.launch()
        emulator.tap("Receive USB/TCP test")
        emulator.wait_report(lambda r: r["relay"]["peers"] == 1 and r["receivedPcm"]["nonSilentFrames"] > 0 and r["output"]["playbackHeadFrames"] > 0)
        passed("receiver background disconnect and reconnect")
        phone.tap("Stop", SOURCE)
    phone.launch()
    phone.tap("Stop session")
    time.sleep(1)
    phone.tap("Refresh metrics")
    phone.wait_report(lambda r: not r["active"] and not r["capture"]["running"])
    passed("session stop releases capture")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--phone", required=True)
    parser.add_argument("--emulator", default="emulator-5554")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--music-file", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    if not output.is_relative_to(root):
        parser.error("Keep test output inside the repository")
    if (output / "results.json").exists():
        parser.error("Use a new output directory to preserve earlier test results")
    output.mkdir(parents=True, exist_ok=True)
    started_at = utc_now()
    phone, emulator = Device(args.adb, args.phone, "phone"), Device(args.adb, args.emulator, "emulator")
    checks, touched = [], []

    def passed(name):
        checks.append(passed_check(name, phone, emulator))
        print(f"PASS {name}", flush=True)

    try:
        for device in (phone, emulator):
            device.check_foreground({LAB, SOURCE, *LAUNCHERS})
        with Forward(phone):
            for device in (phone, emulator):
                touched.append(device)
                for package in (LAB, SOURCE):
                    device.command("shell", "am", "force-stop", package)
            exercise(phone, emulator, passed, args.music_file)
    except Exception as error:
        checks.append({"name": "run", "result": "failed", "error": str(error)})
        print(f"FAIL {error}", flush=True)
        traceback.print_exc()
        for device in (phone, emulator):
            print(json.dumps({"device": device.label, "latest": device.latest}), flush=True)
    finally:
        for device in touched:
            try:
                device.dismiss_projection()
                if args.music_file and device is phone and device.foreground() == MUSIC:
                    device.command("shell", "input", "keyevent", "4")
            except RuntimeError:
                checks.append({"name": "local preview cleanup", "result": "failed"})
            for package in (LAB, SOURCE):
                try:
                    device.command("shell", "am", "force-stop", package)
                except RuntimeError:
                    checks.append({"name": f"{device.label} cleanup", "result": "failed"})
        result = {"startedAtUtc": started_at, "finishedAtUtc": utc_now(), "transport": "ADB loopback TCP", "physicalBluetoothTested": False, "audibleSyncMeasured": False, "musicLocalFile": args.music_file, "checks": checks, "phone": phone.latest, "emulator": emulator.latest}
        (output / "results.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return int(any(check["result"] == "failed" for check in checks))


if __name__ == "__main__":
    raise SystemExit(main())
