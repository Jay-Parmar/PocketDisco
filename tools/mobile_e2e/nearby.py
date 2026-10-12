import argparse
from contextlib import ExitStack
from copy import deepcopy
import json
from pathlib import Path
import re
import secrets
import sys
import time
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from tools.capture_relay_e2e.run import Device, Forward, LAB, LAUNCHERS, SOURCE, SYSTEM, utc_now
from tools.capture_relay_e2e.sync_run import QuietPlayback, advancing_timing, timing_proof

PRODUCT = "com.pocketdisco.internal"


def product_prompt(nodes):
    return any(re.search(r"\bPocketDisco\b(?! Capture)", node.get("text", "")) for node in nodes)


def local_api_address(node):
    return node.get("text", "").rstrip("/") == "http://127.0.0.1:8000"


class ApiReverse:
    def __init__(self, device):
        self.device, self.created = device, False

    def entries(self):
        return [row.split()[-2:] for row in self.device.command("reverse", "--list").splitlines()]

    def __enter__(self):
        existing = [row for row in self.entries() if "tcp:8000" in row[:1]]
        if existing and existing != [["tcp:8000", "tcp:8000"]]:
            raise RuntimeError("Device API port is mapped elsewhere")
        if not existing:
            self.device.command("reverse", "--no-rebind", "tcp:8000", "tcp:8000")
            self.created = True
        return self

    def __exit__(self, *unused):
        if self.created and ["tcp:8000", "tcp:8000"] in self.entries():
            self.device.command("reverse", "--remove", "tcp:8000")


class ProductDevice(Device):
    def launch_product(self):
        self.command("shell", "am", "start", "-W", "-n", f"{PRODUCT}/com.pocketdisco.MainActivity")
        self.wait_foreground(PRODUCT, {LAB, SOURCE, *LAUNCHERS})

    def find_id(self, identity):
        for attempt in range(10):
            node = next((node for node in self.nodes({PRODUCT}) if node.get("resource-id") == identity), None)
            if node is not None:
                return node
            self.check_foreground({PRODUCT})
            self.scroll(attempt % 8 < 4)
            time.sleep(.2)
        raise AssertionError(f"Product control missing: {identity}")

    def tap_id(self, identity):
        for _ in range(10):
            node = self.find_id(identity)
            if node.get("enabled") == "true":
                self.tap_node(node)
                return
            time.sleep(.3)
        raise AssertionError(f"Product control stayed disabled: {identity}")

    def verify_local_api(self):
        self.tap_id("server-settings-toggle")
        if not local_api_address(self.find_id("server-url-input")):
            raise AssertionError("Use the local test API; the saved server address was left intact")
        self.tap_id("server-settings-toggle")

    def fill_id(self, identity, value):
        if not re.fullmatch(r"[A-Za-z ]+", value):
            raise ValueError("Use plain test names")
        node = self.find_id(identity)
        if node.get("text") == value:
            return
        if node.get("text") and node.get("text") != node.get("hint"):
            raise AssertionError("Use an empty test form; existing input was left intact")
        self.tap_node(node)
        self.command("shell", "input", "text", value.replace(" ", "%s"))
        self.command("shell", "input", "keyevent", "4")

    def native(self, text):
        self.tap(text, PRODUCT)

    def fill_session(self, code):
        node = self.find("Session code", PRODUCT, description=True)
        if not re.fullmatch(r"[0-9a-f]{32}", node.get("text", "")):
            raise AssertionError("Unexpected nearby code field")
        self.tap_node(node)
        self.command("shell", "input", "keyevent", "123", *(["67"] * 32))
        self.command("shell", "input", "text", code)
        self.command("shell", "input", "keyevent", "4")

    def approve_capture(self):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            nodes = self.nodes({PRODUCT, *SYSTEM})
            permission = [node for node in nodes if node.get("package", "").endswith("permissioncontroller")]
            if permission:
                if not product_prompt(permission):
                    raise AssertionError("Permission prompt is not for PocketDisco")
                choice = next((node for node in permission if node.get("resource-id", "").split("/")[-1]
                               in {"permission_allow_foreground_only_button", "permission_allow_button"}), None)
            else:
                system = [node for node in nodes if node.get("package") == "com.android.systemui"]
                choice = next((node for node in system if node.get("text") in {"Start now", "Start recording", "Share screen", "Share", "Start"}
                               and node.get("enabled") == "true"), None)
                if choice is not None:
                    if not product_prompt(system):
                        raise AssertionError("Projection prompt is not for PocketDisco")
                    self.tap_node(choice)
                    self.wait_foreground(PRODUCT, SYSTEM)
                    return
            if choice is not None:
                self.tap_node(choice)
            time.sleep(.3)
        raise AssertionError("PocketDisco capture consent did not appear")

    def native_report(self):
        self.native("Show details")
        for _ in range(4):
            for node in self.nodes({PRODUCT}):
                if node.get("text", "").startswith("{"):
                    report = json.loads(node.get("text"))
                    if "sentPcm" in report and "relay" in report:
                        return report
            self.check_foreground({PRODUCT})
            self.scroll()
        raise AssertionError("Nearby report missing from the product UI")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--phone", required=True)
    parser.add_argument("--emulator", default="emulator-5554")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.phone == args.emulator or not args.emulator.startswith("emulator-"):
        parser.error("Use a distinct phone and emulator")
    output = args.output.resolve()
    if not output.is_relative_to(ROOT) or (output / "results.json").exists():
        parser.error("Use a new output directory inside the repository")
    output.mkdir(parents=True, exist_ok=True)
    phone = ProductDevice(args.adb, args.phone, "product phone")
    receiver = Device(args.adb, args.emulator, "lab receiver")
    checks, source_report, audio_report = [], {}, {}
    local_api_verified = False
    started = utc_now()

    def passed(name):
        checks.append({"name": name, "result": "passed", "atUtc": utc_now()})
        print(f"PASS {name}", flush=True)

    try:
        phone.check_foreground({PRODUCT, LAB, SOURCE, *LAUNCHERS})
        receiver.check_foreground({LAB, SOURCE, *LAUNCHERS})
        with urlopen("http://127.0.0.1:8000/healthz", timeout=5) as response:
            if json.load(response).get("mode") != "local_test":
                raise AssertionError("The host API is not in isolated local-test mode")
        with ExitStack() as stack:
            stack.enter_context(QuietPlayback(phone))
            stack.enter_context(QuietPlayback(receiver))
            stack.enter_context(ApiReverse(phone))
            stack.enter_context(Forward(phone))
            try:
                phone.launch_product()
                phone.verify_local_api()
                local_api_verified = True
                phone.fill_id("display-name-input", "Nearby Host")
                phone.fill_id("room-name-input", "Nearby Check")
                phone.tap_id("create-room-button")
                phone.find_id("room-screen")
                passed("private room created in the internal product")
                phone.tap_id("nearby-open")
                phone.find("Pass the sound.", PRODUCT)
                code = secrets.token_hex(16)
                phone.fill_session(code)
                phone.native("Host USB/TCP test")
                receiver.launch()
                receiver.fill_code(code)
                receiver.tap("Receive USB/TCP test")
                receiver.wait_report(lambda r: r["relay"]["peers"] == 1)
                passed("product nearby engine accepts an authenticated receiver")
                phone.native("Start capture")
                phone.approve_capture()
                receiver.wait_report(timing_proof, timeout=30)
                phone.native("Open generated source")
                phone.wait_foreground(SOURCE, {PRODUCT})
                phone.tap("Play allowed", SOURCE)
                receiver.wait_report(lambda r: timing_proof(r) and bool(r["receivedPcm"]["first100AudibleFramesSha256"]), timeout=30)
                before = deepcopy(receiver.latest)
                audio_report = deepcopy(receiver.wait_report(lambda r: advancing_timing(r, before)))
                phone.tap("Stop", SOURCE)
                phone.check_foreground({SOURCE})
                phone.command("shell", "input", "keyevent", "4")
                phone.wait_foreground(PRODUCT, {SOURCE})
                source_report = phone.native_report()
                if source_report["sentPcm"]["first100AudibleFramesSha256"] != audio_report["receivedPcm"]["first100AudibleFramesSha256"]:
                    raise AssertionError("Product and receiver PCM hashes differ")
                passed("generated audio from the product reaches scheduled receiver output")
                phone.command("shell", "input", "keyevent", "4")
                phone.find_id("room-screen")
                for attempt in range(20):
                    services = phone.command("shell", "dumpsys", "activity", "services", PRODUCT)
                    if "com.pocketdisco.capturelab.CaptureService" not in services:
                        break
                    if attempt == 19:
                        raise AssertionError("Capture service remained after returning to the room")
                    time.sleep(.2)
                receiver.wait_report(lambda r: r["relay"]["peers"] == 0 and not r["relay"]["clockReady"]
                                     and r["output"]["timingState"] != "playing")
                passed("Back returns to the room and stops capture and receiver timing")
            finally:
                for device, packages in ((phone, (PRODUCT, SOURCE)), (receiver, (LAB, SOURCE))):
                    for package in packages:
                        try:
                            device.command("shell", "am", "force-stop", package)
                        except Exception:
                            checks.append({"name": "owned app cleanup", "result": "failed"})
    except Exception as error:
        checks.append({"name": "run", "result": "failed", "error": str(error)})
        print(f"FAIL {error}", flush=True)
    finally:
        result = {"startedAtUtc": started, "finishedAtUtc": utc_now(), "transport": "ADB loopback TCP",
                  "acousticSyncMeasured": False,
                  "backend": "isolated local test" if local_api_verified else "not verified", "checks": checks,
                  "source": source_report, "playingReceiver": audio_report, "receiver": receiver.latest}
        (output / "results.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return int(any(check["result"] == "failed" for check in checks))


if __name__ == "__main__":
    raise SystemExit(main())
