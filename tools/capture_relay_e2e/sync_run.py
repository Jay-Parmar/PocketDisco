import argparse
from contextlib import ExitStack
from copy import deepcopy
import json
from pathlib import Path
import re
import secrets
import time

from run import Device, Forward, LAB, LAUNCHERS, MUSIC, SOURCE, SYSTEM, audio_proof, names_lab, utc_now


REVERSE_PORT = "tcp:18991"


class QuietPlayback:
    def __init__(self, device):
        self.device, self.previous = device, None

    def volume(self):
        result = self.device.command("shell", "cmd", "media_session", "volume", "--stream", "3", "--get")
        match = re.search(r"volume is (\d+) in range \[(\d+)\.\.(\d+)\]", result)
        if match is None:
            raise RuntimeError("Cannot verify the device's media volume")
        return tuple(map(int, match.groups()))

    def set_volume(self, value):
        self.device.command("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", str(value))

    def __enter__(self):
        current, minimum, maximum = self.volume()
        if not minimum <= 2 <= maximum:
            raise RuntimeError("Unsupported media volume range")
        if current > 2:
            self.previous = current
            self.set_volume(2)
        return self

    def __exit__(self, *unused):
        if self.previous is not None and self.volume()[0] == 2:
            self.set_volume(self.previous)


class Reverse:
    def __init__(self, device):
        self.device, self.created = device, False

    def entries(self):
        return [row.split() for row in self.device.command("reverse", "--list").splitlines()]

    def __enter__(self):
        if any(REVERSE_PORT in row for row in self.entries()):
            raise RuntimeError("USB receiver port already has a mapping")
        self.device.command("reverse", "--no-rebind", REVERSE_PORT, "tcp:18990")
        self.created = True
        return self

    def __exit__(self, *unused):
        if self.created and any(row[-2:] == [REVERSE_PORT, "tcp:18990"] for row in self.entries()):
            self.device.command("reverse", "--remove", REVERSE_PORT)


def bluetooth_permission(device):
    for _ in range(8):
        nodes = device.nodes({LAB, *SYSTEM})
        permission = [node for node in nodes if node.get("package", "").endswith("permissioncontroller")]
        if not permission:
            return
        if not names_lab(permission):
            raise AssertionError("Bluetooth prompt does not identify the lab")
        allow = next((node for node in permission if node.get("resource-id", "").endswith("/permission_allow_button")), None)
        if allow is None:
            raise AssertionError("Bluetooth permission control missing")
        device.tap_node(allow)
        time.sleep(.3)
    raise AssertionError("Bluetooth permission did not settle")


def timing_proof(report):
    relay, output = report["relay"], report["output"]
    if not relay.get("clockReady") or relay.get("timedFrames", 0) == 0:
        return False
    if output.get("timingState") != "playing" or output.get("scheduledStarts", 0) == 0:
        return False
    uncertainty = relay.get("clockUncertaintyNanos")
    if uncertainty is None or not 0 <= uncertainty <= 100_000_000:
        return False
    if not 0 <= output.get("queuedFrames", -1) <= 100:
        return False
    error = output.get("startErrorNanos")
    output_error = output.get("outputErrorNanos")
    return (error is not None and abs(error) <= 20_000_000
            and output_error is not None and abs(output_error) <= 20_000_000
            and output.get("playbackHeadFrames", 0) > 0)


def advancing_timing(report, previous):
    return (timing_proof(report)
            and report.get("reportSequence", 0) > previous.get("reportSequence", 0)
            and report["relay"]["timedFrames"] > previous["relay"]["timedFrames"]
            and report["output"]["writtenBytes"] > previous["output"]["writtenBytes"])


def stop_devices(devices, source, music_file, checks):
    for device in devices:
        try:
            device.dismiss_projection()
            if music_file and device is source and device.foreground() == MUSIC:
                device.command("shell", "input", "keyevent", "4")
        except Exception:
            checks.append({"name": f"{device.label} preview cleanup", "result": "failed"})
        for package in (LAB, SOURCE):
            try:
                device.command("shell", "am", "force-stop", package)
            except Exception:
                checks.append({"name": f"{device.label} app cleanup", "result": "failed"})


def exercise(source, receivers, transport, peer_name, music_file, passed):
    code = secrets.token_hex(16)
    for device in (source, *receivers):
        device.launch()
        device.fill_code(code)
    if transport == "mixed":
        source.tap("Host paired Bluetooth")
        bluetooth_permission(source)
        receivers[0].tap("Receive paired Bluetooth")
        bluetooth_permission(receivers[0])
        receivers[0].tap(peer_name)
        for device in (source, receivers[0]):
            device.wait_report(lambda r: r["relay"]["peers"] == 1)
        receivers[0].tap("Host USB/TCP test")
        receivers[1].tap("Receive USB/TCP test")
        receivers[0].wait_report(lambda r: r["relay"]["peers"] == 2)
        receivers[1].wait_report(lambda r: r["relay"]["peers"] == 1)
    else:
        source.tap("Host USB/TCP test")
        for device in receivers:
            device.tap("Receive USB/TCP test" if device.serial.startswith("emulator-") else "Receive USB phone test")
        source.wait_report(lambda r: r["relay"]["peers"] == 2)
        for device in receivers:
            device.wait_report(lambda r: r["relay"]["peers"] == 1)
    passed("two authenticated receivers")
    if music_file:
        source.tap("Music app local test file")
    source.tap("Start capture")
    source.consent(True)
    source.wait_report(lambda r: r["capture"]["running"] and r["capture"]["frames"] > 0)
    for device in receivers:
        device.wait_report(timing_proof, timeout=30)
    passed("both receivers scheduled against the source clock")
    source.tap("Open generated source")
    source.wait_foreground(SOURCE, {LAB})
    source.tap("Open generated file in music app" if music_file else "Play allowed", SOURCE)
    if music_file:
        source.wait_foreground(MUSIC, {SOURCE})
    for device in receivers:
        audio_proof(source, device)
    passed("matching generated audio on both receivers")
    samples = []
    for _ in range(8):
        time.sleep(1)
        sample = {}
        for device in receivers:
            previous = deepcopy(device.latest)
            report = device.wait_report(lambda r: advancing_timing(r, previous), timeout=5)
            sample[device.label] = {key: deepcopy(report[key]) for key in ("reportSequence", "relay", "output")}
        samples.append({"atUtc": utc_now(), "receivers": sample})
    passed("sustained scheduled output on both receivers", samples=samples)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--receiver", required=True, action="append")
    parser.add_argument("--transport", choices=("usb", "mixed"), default="usb")
    parser.add_argument("--peer-name")
    parser.add_argument("--music-file", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if len(args.receiver) != 2 or len(set([args.source, *args.receiver])) != 3:
        parser.error("Use one source and two distinct receivers")
    if args.transport == "mixed" and (not args.peer_name or not args.receiver[1].startswith("emulator-")):
        parser.error("Mixed transport needs the source's paired name and an emulator as the second receiver")
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    if not output.is_relative_to(root) or (output / "results.json").exists():
        parser.error("Use a new output directory inside the repository")
    output.mkdir(parents=True, exist_ok=True)
    source = Device(args.adb, args.source, "source")
    receivers = [Device(args.adb, serial, f"receiver{index + 1}") for index, serial in enumerate(args.receiver)]
    devices, checks, touched = [source, *receivers], [], []
    started_at = utc_now()

    def passed(name, **evidence):
        checks.append({"name": name, "result": "passed", "atUtc": utc_now(),
                       "latestObservations": {device.label: deepcopy(device.latest) for device in devices}, **evidence})
        print(f"PASS {name}", flush=True)

    try:
        for device in devices:
            device.check_foreground({LAB, SOURCE, *LAUNCHERS})
        with ExitStack() as stack:
            for device in devices:
                stack.enter_context(QuietPlayback(device))
            stack.enter_context(Forward(receivers[0] if args.transport == "mixed" else source))
            if args.transport == "usb":
                for device in receivers:
                    if not device.serial.startswith("emulator-"):
                        stack.enter_context(Reverse(device))
            try:
                for device in devices:
                    touched.append(device)
                    for package in (LAB, SOURCE):
                        device.command("shell", "am", "force-stop", package)
                exercise(source, receivers, args.transport, args.peer_name, args.music_file, passed)
            finally:
                stop_devices(touched, source, args.music_file, checks)
    except Exception as error:
        checks.append({"name": "run", "result": "failed", "error": str(error)})
        print(f"FAIL {error}", flush=True)
    finally:
        result = {"startedAtUtc": started_at, "finishedAtUtc": utc_now(), "transport": args.transport,
                  "audibleSyncMeasured": False, "musicLocalFile": args.music_file,
                  "checks": checks, "latestObservations": {device.label: device.latest for device in devices}}
        (output / "results.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return int(any(check["result"] == "failed" for check in checks))


if __name__ == "__main__":
    raise SystemExit(main())
