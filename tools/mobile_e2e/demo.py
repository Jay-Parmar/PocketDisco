import argparse
import re
import sys
import time
from contextlib import ExitStack
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from tools.capture_relay_e2e.run import LAUNCHERS, bounds, system_bar_viewport, utc_now
from tools.capture_relay_e2e.sync_run import QuietPlayback
from tools.mobile_e2e.rooms import (
    NAMES,
    PRODUCT,
    ROOM_NAME,
    ApiMapping,
    RoomDevice,
    RoomFailure,
    health,
    prepare_output,
    write_result,
)
from tools.mobile_e2e.run import port_number

IDLE = "Ready for a sound check"
PAUSED = "Paused"
ENDED = "Demo finished"
DURATION = 24
CACHED_CONTROLS = {"demo-play-pause", "leave-room-button"}
FAILURE_REASONS = {
    "api_mapping_changed",
    "ambiguous_api_mapping",
    "backend_health_failed",
    "backend_not_production",
    "backend_redirect_refused",
    "conflicting_api_mapping",
    "control_not_safely_tappable",
    "control_outside_viewport",
    "existing_input_preserved",
    "expected_ui_state_missing",
    "leave_confirmation_not_visible",
    "product_home_not_confirmed",
    "saved_room_present",
    "saved_server_preserved",
    "unrecognized_api_mapping",
    "demo_back_seek_mismatch",
    "demo_control_missing",
    "demo_control_stale",
    "demo_forward_seek_mismatch",
    "demo_leave_missed_active_clip",
    "demo_native_player_stayed_active",
    "demo_natural_end_missing",
    "demo_playback_error",
    "demo_playhead_did_not_advance",
    "demo_playhead_invalid",
    "demo_position_did_not_stay_paused",
    "demo_replay_position_invalid",
    "demo_run_deadline_reached",
    "demo_unexpected_autoplay",
    "demo_viewport_changed",
}


def playhead_seconds(label):
    match = re.search(r"(?<!\d)(\d+):([0-5]\d) of (\d+):([0-5]\d)(?!\d)", label)
    if match is None:
        raise RoomFailure("demo_playhead_invalid")
    minutes, seconds, duration_minutes, duration_seconds = map(int, match.groups())
    position = minutes * 60 + seconds
    if duration_minutes * 60 + duration_seconds != DURATION or position > DURATION:
        raise RoomFailure("demo_playhead_invalid")
    return position


def playback_states(raw, uid):
    if len(raw) > 2_000_000 or raw.count("PlaybackActivityMonitor dump time:") != 1:
        return None
    section = raw.split("PlaybackActivityMonitor dump time:", 1)[1]
    blocks = re.findall(
        r"^[ \t]+players:[ \t]*\r?\n(.*?)^[ \t]+ducked players piids:[ \t]*$",
        section,
        re.MULTILINE | re.DOTALL,
    )
    if len(blocks) != 1:
        return None
    states = set()
    for line in blocks[0].splitlines():
        if not line.strip():
            continue
        match = re.match(
            r"^[ \t]*(?:\(not logged\))?[ \t]*AudioPlaybackConfiguration piid:\d+ "
            r".*?\btype:(.+?) u/pid:(\d+)/(\d+) state:(\w+)(?:\s|$)",
            line,
        )
        if match is None or match[4] not in {
            "idle",
            "started",
            "paused",
            "stopped",
            "released",
        }:
            return None
        if int(match[2]) == uid and match[1] == "android.media.AudioTrack":
            states.add(match[4])
    return states


class NativePlayback:
    def __init__(self, device):
        self.device, self.uid = device, None
        try:
            raw = device.command(
                "shell", "cmd", "package", "list", "packages", "-U", PRODUCT
            )
        except RuntimeError:
            return
        match = re.fullmatch(r"package:" + re.escape(PRODUCT) + r" uid:(\d+)\s*", raw)
        if match and int(match[1]) >= 10000:
            self.uid = int(match[1])

    def states(self):
        if self.uid is None:
            return None
        try:
            raw = self.device.command("shell", "dumpsys", "audio")
        except RuntimeError:
            return None
        return playback_states(raw, self.uid)


@dataclass(frozen=True)
class Control:
    identity: str
    rectangle: tuple
    viewport: tuple
    captured_at: float


class DemoDevice(RoomDevice):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.owns_product = False

    def ensure_time(self, duration=0):
        if (
            duration < 0
            or duration > 30
            or time.monotonic() + duration >= self.deadline
        ):
            raise RoomFailure("demo_run_deadline_reached")

    def wait(self, duration, allowed=None):
        self.ensure_time(duration)
        self.check_foreground(allowed or {PRODUCT})
        time.sleep(duration)
        self.ensure_time()
        self.check_foreground(allowed or {PRODUCT})

    def read_status(self, status):
        def read(nodes):
            if any(node.get("resource-id") == "demo-error" for node in nodes):
                raise RoomFailure("demo_playback_error")
            labels = {node.get("resource-id"): node for node in nodes}
            if "demo-status" not in labels or "demo-progress" not in labels:
                return None
            if labels["demo-status"].get("text") != status:
                return None
            return (playhead_seconds(labels["demo-progress"].get("content-desc", "")),)

        return self.wait_ui(read)[0]

    def stable_status(self, status):
        position = self.read_status(status)
        self.wait(1)
        if self.read_status(status) != position:
            raise RoomFailure("demo_position_did_not_stay_paused")
        return position

    def controls(self, *identities):
        if not identities or not set(identities) <= CACHED_CONTROLS:
            raise RoomFailure("demo_control_missing")
        for attempt in range(8):
            self.ensure_time()
            nodes = self.nodes({PRODUCT})
            captured_at = time.monotonic()
            viewport = system_bar_viewport(
                self.command("shell", "dumpsys", "window", "displays")
            )
            selected = {}
            clipped = None
            for identity in identities:
                matches = [
                    node for node in nodes if node.get("resource-id") == identity
                ]
                if len(matches) != 1:
                    self.find_id(identity)
                    break
                node = matches[0]
                if node.get("enabled") != "true":
                    break
                if (
                    re.fullmatch(r"\[\d+,\d+\]\[\d+,\d+\]", node.get("bounds", ""))
                    is None
                ):
                    raise RoomFailure("control_outside_viewport")
                rectangle = tuple(bounds(node))
                left_edge, top_edge, right_edge, bottom_edge = viewport
                for parent in nodes:
                    if parent.get("class") == "android.widget.ScrollView" and any(
                        child is node for child in parent.iter()
                    ):
                        left, top, right, bottom = bounds(parent)
                        left_edge, top_edge = max(left_edge, left), max(top_edge, top)
                        right_edge, bottom_edge = (
                            min(right_edge, right),
                            min(bottom_edge, bottom),
                        )
                left, top, right, bottom = rectangle
                if not left_edge <= left < right <= right_edge:
                    raise RoomFailure("control_outside_viewport")
                if not top_edge <= top < bottom <= bottom_edge:
                    clipped = top >= top_edge
                    break
                selected[identity] = Control(identity, rectangle, viewport, captured_at)
            if len(selected) == len(identities):
                return selected
            if clipped is not None and attempt < 7:
                self.check_foreground({PRODUCT})
                self.scroll(down=clipped, small=True)
            self.wait(0.25)
        raise RoomFailure("control_not_safely_tappable")

    def tap_control(self, control):
        self.ensure_time()
        age = time.monotonic() - control.captured_at
        if control.identity not in CACHED_CONTROLS or not 0 <= age <= 12:
            raise RoomFailure("demo_control_stale")
        self.check_foreground({PRODUCT})
        current = system_bar_viewport(
            self.command("shell", "dumpsys", "window", "displays")
        )
        if current != control.viewport:
            raise RoomFailure("demo_viewport_changed")
        left, top, right, bottom = control.rectangle
        if not (
            current[0] <= left < right <= current[2]
            and current[1] <= top < bottom <= current[3]
        ):
            raise RoomFailure("control_outside_viewport")
        self.ensure_time()
        if time.monotonic() - control.captured_at > 12:
            raise RoomFailure("demo_control_stale")
        self.command(
            "shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2)
        )

    def background(self):
        self.ensure_time()
        self.check_foreground({PRODUCT})
        self.command("shell", "input", "keyevent", "3")


def native_stop_verified(device, probe, before, deadline):
    if before is None or "started" not in before:
        return False
    for _ in range(5):
        if time.monotonic() >= deadline:
            return False
        after = probe.states()
        if time.monotonic() >= deadline or after is None:
            return False
        if "started" not in after:
            device.wait(1)
            if time.monotonic() >= deadline:
                return False
            stable = probe.states()
            if time.monotonic() >= deadline or stable is None:
                return False
            if "started" not in stable:
                return True
        device.wait(0.5)
        if time.monotonic() >= deadline:
            return False
    raise RoomFailure("demo_native_player_stayed_active")


def exercise(device, name, passed, not_verified):
    result = {"nativeStopVerified": False}
    device.fill_id("display-name-input", name)
    device.fill_id("room-name-input", ROOM_NAME)
    device.owns_product = True
    device.tap_id("create-room-button")
    device.connected()
    passed("fresh private room opens for the local audio test")
    device.tap_id("room-nav-audio")
    if device.stable_status(IDLE) != 0:
        raise RoomFailure("demo_unexpected_autoplay")
    passed("room entry does not auto-play the demo")

    control = device.controls("demo-play-pause")["demo-play-pause"]
    device.tap_control(control)
    device.wait(4)
    device.tap_control(control)
    paused = device.stable_status(PAUSED)
    if not 2 <= paused <= 12:
        raise RoomFailure("demo_playhead_did_not_advance")
    result["pausedPositionSeconds"] = paused
    passed("native-backed playhead advances before explicit pause")

    device.tap_id("demo-seek-forward")
    forward = device.stable_status(PAUSED)
    if forward != min(DURATION, paused + 5):
        raise RoomFailure("demo_forward_seek_mismatch")
    device.tap_id("demo-seek-back")
    backward = device.stable_status(PAUSED)
    if backward != max(0, forward - 5):
        raise RoomFailure("demo_back_seek_mismatch")
    result.update(forwardPositionSeconds=forward, backwardPositionSeconds=backward)
    passed("five-second forward and backward seeks remain paused")

    device.tap_control(device.controls("demo-play-pause")["demo-play-pause"])
    device.wait(DURATION - backward + 2)
    if device.read_status(ENDED) != DURATION:
        raise RoomFailure("demo_natural_end_missing")
    passed("the generated clip reaches its natural end")

    device.tap_control(device.controls("demo-play-pause")["demo-play-pause"])
    device.wait(3)
    device.background()
    device.wait(1, allowed=LAUNCHERS)
    device.launch_product()
    device.connected()
    device.tap_id("room-nav-audio")
    replay = device.stable_status(PAUSED)
    if not 1 <= replay < 12:
        raise RoomFailure("demo_replay_position_invalid")
    result["replayPositionSeconds"] = replay
    passed("replay starts near the beginning again")
    passed("background and return keep playback paused")

    device.stop_product()
    device.launch_product()
    device.connected()
    device.tap_id("room-nav-audio")
    if device.stable_status(IDLE) != 0:
        raise RoomFailure("demo_unexpected_autoplay")
    passed("process restart restores the room without auto-playing")

    probe = NativePlayback(device)
    controls = device.controls("demo-play-pause", "leave-room-button")
    started = time.monotonic()
    device.tap_control(controls["demo-play-pause"])
    device.wait(3)
    before = probe.states()
    device.tap_control(controls["leave-room-button"])
    device.confirm_leave()
    elapsed = time.monotonic() - started
    if not 0 < elapsed < 18:
        raise RoomFailure("demo_leave_missed_active_clip")
    result["leaveCompletedAfterPlaySeconds"] = round(elapsed, 3)
    passed("leaving after starting the demo returns to a clean welcome screen")
    proof_deadline = started + DURATION - 2
    result["nativeStopVerified"] = native_stop_verified(
        device, probe, before, proof_deadline
    )
    if result["nativeStopVerified"]:
        passed("native player stops after leaving")
    else:
        not_verified(
            "native player stops after leaving",
            "native_stop_window_expired"
            if time.monotonic() >= proof_deadline
            else "system_playback_state_unavailable",
        )
    return result


def parse_args(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--device", action="append", required=True)
    parser.add_argument("--host-port", type=port_number, default=18080)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if (
        len(args.device) not in (1, 2)
        or len(set(args.device)) != len(args.device)
        or any(not serial.strip() for serial in args.device)
    ):
        parser.error("Use one or two distinct explicit devices")
    return args


def main():
    args = parse_args()
    try:
        output = prepare_output(args.output)
    except (RoomFailure, OSError, ValueError):
        print("FAIL use_new_local_tools_output", flush=True)
        return 2
    report = {
        "startedAtUtc": utc_now(),
        "deviceCount": len(args.device),
        "package": PRODUCT,
        "backend": "not verified",
        "hostPort": args.host_port,
        "scope": "Generated 24-second local demo on each device separately",
        "transport": "ADB loopback through the configured SSH tunnel",
        "acousticSyncMeasured": False,
        "publicNetworkTested": False,
        "sharedPlaybackTested": False,
        "audioRecorded": False,
        "checks": [],
        "devices": [],
    }

    def record(name, result="passed", device=None, reason=None):
        check = {"name": name, "result": result, "atUtc": utc_now()}
        if device:
            check["device"] = device
        if reason:
            check["reason"] = reason
        report["checks"].append(check)
        print(f"{result.upper()} {device + ': ' if device else ''}{name}", flush=True)

    try:
        health(args.host_port)
        report["backend"] = "production"
        record("production backend is healthy through the tunnel")
        devices = [
            DemoDevice(args.adb, serial, f"device {index + 1}", names=(NAMES[index],))
            for index, serial in enumerate(args.device)
        ]
        for device in devices:
            device.check_foreground({PRODUCT, *LAUNCHERS})
        with ExitStack() as stack:
            for device in devices:
                stack.enter_context(ApiMapping(device, args.host_port))
            for device, name in zip(devices, NAMES[: len(devices)], strict=True):
                device.launch_product()
                device.verify_welcome(name)
            record("empty welcome screens use the expected device API address")
            for device, name in zip(devices, NAMES[: len(devices)], strict=True):
                with QuietPlayback(device):
                    try:
                        observations = exercise(
                            device,
                            name,
                            lambda check, label=device.label: record(
                                check, device=label
                            ),
                            lambda check, reason, label=device.label: record(
                                check, "not_verified", device=label, reason=reason
                            ),
                        )
                        report["devices"].append(
                            {"label": device.label, **observations}
                        )
                    except (Exception, KeyboardInterrupt):
                        if device.owns_product:
                            try:
                                device.command("shell", "am", "force-stop", PRODUCT)
                            except (RuntimeError, OSError):
                                record(
                                    "owned product cleanup",
                                    "failed",
                                    device=device.label,
                                    reason="product_stop_failed",
                                )
                        raise
    except (Exception, KeyboardInterrupt) as error:  # noqa: BLE001
        reason = (
            str(error)
            if isinstance(error, RoomFailure) and str(error) in FAILURE_REASONS
            else "demo_check_failed"
        )
        record("run", "failed", reason=reason)
        print(f"FAIL {reason}", flush=True)
    report["finishedAtUtc"] = utc_now()
    try:
        write_result(output, report)
    except OSError:
        print("FAIL result_write_failed", flush=True)
        return 1
    return int(any(check["result"] == "failed" for check in report["checks"]))


if __name__ == "__main__":
    raise SystemExit(main())
