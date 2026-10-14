import argparse
import io
import json
import unittest
from pathlib import Path
from unittest.mock import MagicMock, Mock, call, patch
from xml.etree.ElementTree import Element

from demo import (
    PRODUCT,
    Control,
    DemoDevice,
    NativePlayback,
    RoomFailure,
    exercise,
    main,
    parse_args,
    playback_states,
    playhead_seconds,
)


def player_dump(rows):
    return (
        "PlaybackActivityMonitor dump time: 12:00:00\n"
        "  playback listeners:\n\n  players:\n"
        + "\n".join(rows)
        + "\n  ducked players piids:\n"
    )


def player(uid=10001, state="started", kind="android.media.AudioTrack"):
    return (
        f"  AudioPlaybackConfiguration piid:4 deviceIds:[1] type:{kind} "
        f"u/pid:{uid}/101 state:{state} attr:AudioAttributes sessionId:8"
    )


class InputTest(unittest.TestCase):
    def test_one_or_two_distinct_explicit_devices(self):
        base = ["--adb", "unused", "--output", ".local-tools/demo-unused"]
        for serials in (("test-phone",), ("test-phone", "test-emulator")):
            arguments = base + [
                part for serial in serials for part in ("--device", serial)
            ]
            self.assertEqual(parse_args(arguments).device, list(serials))
        for serials in ((), ("same", "same"), ("one", "two", "three"), ("",), ("  ",)):
            arguments = base + [
                part for serial in serials for part in ("--device", serial)
            ]
            with (
                self.subTest(serials=serials),
                patch("sys.stderr", new=io.StringIO()),
                self.assertRaises(SystemExit),
            ):
                parse_args(arguments)

    def test_only_the_generated_clip_playhead_is_accepted(self):
        self.assertEqual(playhead_seconds("Demo playhead, 0:04 of 0:24"), 4)
        self.assertEqual(playhead_seconds("0:24 of 0:24"), 24)
        for value in (
            "",
            "0:25 of 0:24",
            "0:04 of 3:24",
            "0:99 of 0:24",
            "0:04 / 0:24",
        ):
            with self.subTest(value=value), self.assertRaises(RoomFailure):
                playhead_seconds(value)


class NativeStateTest(unittest.TestCase):
    def test_only_current_product_audio_track_states_are_used(self):
        raw = player_dump(
            [
                player(),
                player(10002),
                player(10001, "started", "android.media.SoundPool"),
            ]
        )
        self.assertEqual(playback_states(raw, 10001), {"started"})
        self.assertEqual(playback_states(player_dump([player(10002)]), 10001), set())
        self.assertEqual(playback_states(player_dump([]), 10001), set())
        history = "event log: " + player() + "\n"
        self.assertEqual(
            playback_states(player_dump([player(state="paused")]) + history, 10001),
            {"paused"},
        )

    def test_unknown_or_ambiguous_dump_is_unavailable_not_silence(self):
        for raw in (
            "",
            "permission denied",
            player_dump(["unexpected entry"]),
            player_dump([player(state="unknown")]),
            player_dump([]) * 2,
        ):
            with self.subTest(raw=raw):
                self.assertIsNone(playback_states(raw, 10001))

    def test_package_uid_must_be_exact_and_is_not_a_report_field(self):
        device = Mock()
        device.command.side_effect = [
            f"package:{PRODUCT} uid:10001\n",
            player_dump([player()]),
        ]
        probe = NativePlayback(device)
        self.assertEqual(probe.states(), {"started"})
        self.assertEqual(
            device.command.call_args_list,
            [
                call("shell", "cmd", "package", "list", "packages", "-U", PRODUCT),
                call("shell", "dumpsys", "audio"),
            ],
        )

    def test_unavailable_uid_does_not_read_other_audio_state(self):
        for output in (
            "package:another.app uid:10001",
            f"package:{PRODUCT}.other uid:10001",
            "permission denied",
        ):
            device = Mock()
            device.command.return_value = output
            probe = NativePlayback(device)
            self.assertIsNone(probe.states())
            self.assertEqual(device.command.call_count, 1)


class DeviceTest(unittest.TestCase):
    def setUp(self):
        self.device = DemoDevice(
            "unused", "private-device", "device 1", names=("Room Host",)
        )
        self.viewport = (0, 96, 1080, 2298)
        self.control = Control(
            "demo-play-pause", (100, 800, 900, 900), self.viewport, 100.0
        )

    def test_cached_tap_rechecks_foreground_viewport_and_age_without_ui_dump(self):
        with (
            patch("demo.time.monotonic", return_value=104.0),
            patch.object(self.device, "check_foreground") as guard,
            patch.object(self.device, "command") as command,
            patch("demo.system_bar_viewport", return_value=self.viewport),
            patch.object(self.device, "nodes") as nodes,
        ):
            self.device.tap_control(self.control)
        guard.assert_called_once_with({PRODUCT})
        nodes.assert_not_called()
        self.assertEqual(
            command.call_args_list,
            [
                call("shell", "dumpsys", "window", "displays"),
                call("shell", "input", "tap", "500", "850"),
            ],
        )

    def test_old_or_unknown_cached_control_cannot_tap(self):
        for control, now in (
            (self.control, 113.0),
            (Control("unknown", self.control.rectangle, self.viewport, 100.0), 101.0),
        ):
            with (
                self.subTest(now=now),
                patch("demo.time.monotonic", return_value=now),
                patch.object(self.device, "command") as command,
                self.assertRaises(RoomFailure),
            ):
                self.device.tap_control(control)
            command.assert_not_called()

    def test_changed_viewport_cannot_reuse_coordinates(self):
        with (
            patch("demo.time.monotonic", return_value=101.0),
            patch.object(self.device, "check_foreground"),
            patch.object(self.device, "command") as command,
            patch("demo.system_bar_viewport", return_value=(0, 96, 2298, 1080)),
            self.assertRaises(RoomFailure),
        ):
            self.device.tap_control(self.control)
        command.assert_called_once_with("shell", "dumpsys", "window", "displays")

    def test_unknown_foreground_cannot_reuse_coordinates(self):
        with (
            patch("demo.time.monotonic", return_value=101.0),
            patch.object(
                self.device,
                "check_foreground",
                side_effect=AssertionError("private foreground"),
            ),
            patch.object(self.device, "command") as command,
            self.assertRaises(AssertionError),
        ):
            self.device.tap_control(self.control)
        command.assert_not_called()

    def test_slow_viewport_check_does_not_extend_cached_control_lifetime(self):
        now = 101.0

        def slow_read(*unused):
            nonlocal now
            now = 113.0
            return ""

        with (
            patch("demo.time.monotonic", side_effect=lambda: now),
            patch.object(self.device, "check_foreground"),
            patch.object(self.device, "command", side_effect=slow_read) as command,
            patch("demo.system_bar_viewport", return_value=self.viewport),
            self.assertRaisesRegex(RoomFailure, "demo_control_stale"),
        ):
            self.device.tap_control(self.control)
        command.assert_called_once_with("shell", "dumpsys", "window", "displays")

    def test_clipped_control_is_scrolled_before_its_bounds_can_be_cached(self):
        parent = Element(
            "node",
            **{"class": "android.widget.ScrollView", "bounds": "[0,300][1080,1800]"},
        )
        clipped = Element(
            "node",
            **{
                "resource-id": "demo-play-pause",
                "enabled": "true",
                "bounds": "[100,1750][900,1900]",
            },
        )
        visible = Element(
            "node",
            **{
                "resource-id": "demo-play-pause",
                "enabled": "true",
                "bounds": "[100,1200][900,1350]",
            },
        )
        parent.append(clipped)
        with (
            patch.object(
                self.device, "nodes", side_effect=[[parent, clipped], [visible]]
            ),
            patch.object(self.device, "command"),
            patch("demo.system_bar_viewport", return_value=self.viewport),
            patch.object(self.device, "check_foreground"),
            patch.object(self.device, "scroll") as scroll,
            patch.object(self.device, "wait"),
        ):
            controls = self.device.controls("demo-play-pause")
        self.assertEqual(controls["demo-play-pause"].rectangle, (100, 1200, 900, 1350))
        scroll.assert_called_once_with(down=True, small=True)

    def test_negative_or_horizontal_clipping_is_not_cached(self):
        for rectangle in ("[-100,300][900,400]", "[100,300][1200,400]"):
            node = Element(
                "node",
                **{
                    "resource-id": "demo-play-pause",
                    "enabled": "true",
                    "bounds": rectangle,
                },
            )
            with (
                self.subTest(rectangle=rectangle),
                patch.object(self.device, "nodes", return_value=[node]),
                patch.object(self.device, "command"),
                patch("demo.system_bar_viewport", return_value=self.viewport),
                self.assertRaisesRegex(RoomFailure, "control_outside_viewport"),
            ):
                self.device.controls("demo-play-pause")

    def test_expired_run_does_not_read_or_cache_controls(self):
        self.device.deadline = 0
        with (
            patch.object(self.device, "nodes") as nodes,
            self.assertRaises(RoomFailure),
        ):
            self.device.controls("demo-play-pause")
        nodes.assert_not_called()

    def test_waits_are_short_and_respect_total_deadline(self):
        self.device.deadline = 110.0
        for duration in (-1, 61, 11):
            with (
                self.subTest(duration=duration),
                patch("demo.time.monotonic", return_value=100.0),
                patch("demo.time.sleep") as sleep,
                self.assertRaises(RoomFailure),
            ):
                self.device.wait(duration)
            sleep.assert_not_called()

    def test_stable_status_requires_unchanged_native_backed_position(self):
        with (
            patch.object(self.device, "read_status", side_effect=[4, 5]),
            patch.object(self.device, "wait"),
            self.assertRaisesRegex(RoomFailure, "demo_position_did_not_stay_paused"),
        ):
            self.device.stable_status("Paused")

    def test_player_error_is_not_accepted_as_paused(self):
        error = Element(
            "node", **{"resource-id": "demo-error", "text": "private details"}
        )
        with (
            patch.object(self.device, "nodes", return_value=[error]),
            self.assertRaisesRegex(RoomFailure, "demo_playback_error"),
        ):
            self.device.read_status("Paused")


class ScenarioTest(unittest.TestCase):
    def device(self):
        device = Mock(owns_product=False)
        device.stable_status.side_effect = [0, 4, 9, 4, 3, 0]
        device.read_status.return_value = 24
        device.controls.return_value = {
            "demo-play-pause": Mock(),
            "leave-room-button": Mock(),
        }
        return device

    def test_complete_generated_demo_flow_leaves_cleanly(self):
        device = self.device()
        passed, not_verified = Mock(), Mock()
        probe = Mock()
        probe.states.side_effect = [{"started"}, set(), set()]
        with (
            patch("demo.NativePlayback", return_value=probe),
            patch("demo.time.monotonic", side_effect=[100.0, 108.0]),
        ):
            result = exercise(device, "Room Host", passed, not_verified)
        self.assertTrue(result["nativeStopVerified"])
        self.assertEqual(result["pausedPositionSeconds"], 4)
        self.assertEqual(result["replayPositionSeconds"], 3)
        self.assertEqual(passed.call_count, 10)
        not_verified.assert_not_called()
        device.confirm_leave.assert_called_once()
        device.stop_product.assert_called_once()
        self.assertEqual(device.tap_id.call_args_list.count(call("room-nav-audio")), 3)
        device.tap_id.assert_any_call("demo-seek-forward")
        device.tap_id.assert_any_call("demo-seek-back")

    def test_no_native_proof_does_not_claim_player_stop(self):
        device = self.device()
        probe = Mock()
        probe.states.return_value = None
        passed, not_verified = Mock(), Mock()
        with (
            patch("demo.NativePlayback", return_value=probe),
            patch("demo.time.monotonic", side_effect=[100.0, 108.0]),
        ):
            result = exercise(device, "Room Host", passed, not_verified)
        self.assertFalse(result["nativeStopVerified"])
        not_verified.assert_called_once_with(
            "native player stops after leaving", "system_playback_state_unavailable"
        )
        self.assertEqual(passed.call_count, 9)

    def test_bad_seek_or_no_advance_stops_before_more_playback(self):
        for positions, reason in (
            ([0, 0], "demo_playhead_did_not_advance"),
            ([0, 4, 8], "demo_forward_seek_mismatch"),
            ([0, 4, 9, 5], "demo_back_seek_mismatch"),
        ):
            device = self.device()
            device.stable_status.side_effect = positions
            with (
                self.subTest(reason=reason),
                patch("demo.NativePlayback"),
                self.assertRaisesRegex(RoomFailure, reason),
            ):
                exercise(device, "Room Host", Mock(), Mock())
            device.confirm_leave.assert_not_called()

    def test_natural_end_cannot_be_mistaken_for_leave_stopping_audio(self):
        device = self.device()
        with (
            patch("demo.NativePlayback"),
            patch("demo.time.monotonic", side_effect=[100.0, 124.0]),
            self.assertRaisesRegex(RoomFailure, "demo_leave_missed_active_clip"),
        ):
            exercise(device, "Room Host", Mock(), Mock())

    def test_native_player_still_active_after_leave_is_failure(self):
        device = self.device()
        probe = Mock()
        probe.states.return_value = {"started"}
        with (
            patch("demo.NativePlayback", return_value=probe),
            patch("demo.time.monotonic", side_effect=[100.0, 108.0]),
            self.assertRaisesRegex(RoomFailure, "demo_native_player_stayed_active"),
        ):
            exercise(device, "Room Host", Mock(), Mock())


class MainTest(unittest.TestCase):
    def arguments(self, count=1):
        return argparse.Namespace(
            adb="unused",
            device=["private-one", "private-two"][:count],
            host_port=18080,
            output=Path(".local-tools/demo-unused"),
        )

    def test_selected_devices_are_preflighted_and_run_separately(self):
        for count in (1, 2):
            devices = [Mock(owns_product=False) for _ in range(count)]
            with (
                self.subTest(count=count),
                patch("demo.parse_args", return_value=self.arguments(count)),
                patch("demo.prepare_output", return_value=Mock()),
                patch("demo.DemoDevice", side_effect=devices),
                patch("demo.health") as health,
                patch("demo.ApiMapping"),
                patch("demo.QuietPlayback"),
                patch(
                    "demo.exercise", return_value={"nativeStopVerified": False}
                ) as scenario,
                patch("demo.write_result") as write,
                patch("builtins.print"),
            ):
                self.assertEqual(main(), 0)
            health.assert_called_once_with(18080)
            self.assertEqual(scenario.call_count, count)
            for device in devices:
                device.verify_welcome.assert_called_once()
                device.command.assert_not_called()
            report = write.call_args.args[1]
            self.assertEqual(report["deviceCount"], count)
            self.assertFalse(report["acousticSyncMeasured"])
            self.assertFalse(report["publicNetworkTested"])
            self.assertFalse(report["sharedPlaybackTested"])

    def test_failure_stops_only_owned_product_then_restores_volume_and_mapping(self):
        device = Mock(owns_product=True)
        quiet, mapping = MagicMock(), MagicMock()
        order = Mock()
        order.attach_mock(device.command, "command")
        order.attach_mock(quiet.__exit__, "volume_exit")
        order.attach_mock(mapping.__exit__, "mapping_exit")
        with (
            patch("demo.parse_args", return_value=self.arguments()),
            patch("demo.prepare_output", return_value=Mock()),
            patch("demo.DemoDevice", return_value=device),
            patch("demo.health"),
            patch("demo.ApiMapping", return_value=mapping),
            patch("demo.QuietPlayback", return_value=quiet),
            patch(
                "demo.exercise",
                side_effect=RuntimeError("private-one invite token raw audio"),
            ),
            patch("demo.write_result") as write,
            patch("builtins.print"),
        ):
            self.assertEqual(main(), 1)
        device.command.assert_called_once_with("shell", "am", "force-stop", PRODUCT)
        self.assertEqual(
            [item[0] for item in order.mock_calls],
            ["command", "volume_exit", "mapping_exit"],
        )
        serialized = json.dumps(write.call_args.args[1])
        for private in ("private-one", "invite token", "raw audio"):
            self.assertNotIn(private, serialized)

    def test_saved_room_preflight_never_stops_or_changes_volume(self):
        device = Mock(owns_product=False)
        device.verify_welcome.side_effect = RoomFailure("saved_room_present")
        with (
            patch("demo.parse_args", return_value=self.arguments()),
            patch("demo.prepare_output", return_value=Mock()),
            patch("demo.DemoDevice", return_value=device),
            patch("demo.health"),
            patch("demo.ApiMapping"),
            patch("demo.QuietPlayback") as quiet,
            patch("demo.exercise") as scenario,
            patch("demo.write_result"),
            patch("builtins.print"),
        ):
            self.assertEqual(main(), 1)
        quiet.assert_not_called()
        scenario.assert_not_called()
        device.command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
