import argparse
import io
import json
import unittest
from contextlib import contextmanager
from pathlib import Path
from unittest.mock import MagicMock, Mock, call, patch
from xml.etree.ElementTree import Element

from rooms import (
    PRODUCT,
    ROOT,
    ApiMapping,
    RoomDevice,
    RoomFailure,
    exercise,
    health,
    main,
    member_present,
    message_present,
    parse_args,
    validate_output,
)


class MappingDevice:
    def __init__(self, mapping=None):
        self.mapping = mapping
        self.calls = []

    def command(self, *args):
        self.calls.append(args)
        if args == ("reverse", "--list"):
            return f"UsbFfs tcp:8000 {self.mapping}\n" if self.mapping else ""
        if args == ("reverse", "--remove", "tcp:8000"):
            self.mapping = None
            return ""
        if args[:3] == ("reverse", "--no-rebind", "tcp:8000"):
            if self.mapping is not None:
                raise RuntimeError("Occupied")
            self.mapping = args[3]
            return ""
        raise AssertionError("Unexpected mocked command")


class MappingTest(unittest.TestCase):
    def test_blank_padding_in_adb_output_is_ignored(self):
        device = Mock()
        for output, expected in (
            ("UsbFfs tcp:8000 tcp:18080\n\n", "tcp:18080"),
            ("\n \r\n", None),
        ):
            with self.subTest(output=output):
                device.command.return_value = output
                self.assertEqual(ApiMapping(device, 18080).current(), expected)

    def test_existing_matching_mapping_is_preserved(self):
        device = MappingDevice("tcp:18080")
        with ApiMapping(device, 18080):
            pass
        self.assertEqual(device.mapping, "tcp:18080")
        self.assertFalse(
            any("--remove" in args or "--no-rebind" in args for args in device.calls)
        )

    def test_conflict_is_not_replaced(self):
        device = MappingDevice("tcp:9999")
        with self.assertRaises(RoomFailure), ApiMapping(device, 18080):
            pass
        self.assertEqual(device.calls, [("reverse", "--list")])
        self.assertEqual(device.mapping, "tcp:9999")

    def test_owned_mapping_is_removed_after_failure(self):
        device = MappingDevice()
        with self.assertRaises(ValueError), ApiMapping(device, 18080):
            self.assertEqual(device.mapping, "tcp:18080")
            raise ValueError("Private exception")
        self.assertIsNone(device.mapping)

    def test_offline_failure_restores_preconfigured_mapping(self):
        device = MappingDevice("tcp:18080")
        with ApiMapping(device, 18080) as mapping:
            with self.assertRaises(ValueError), mapping.offline():
                self.assertIsNone(device.mapping)
                raise ValueError("Private exception")
            self.assertEqual(device.mapping, "tcp:18080")
        self.assertEqual(device.mapping, "tcp:18080")
        self.assertEqual(device.calls.count(("reverse", "--remove", "tcp:8000")), 1)

    def test_offline_failure_restores_then_removes_owned_mapping(self):
        device = MappingDevice()
        with (
            self.assertRaises(ValueError),
            ApiMapping(device, 18080) as mapping,
            mapping.offline(),
        ):
            raise ValueError("Private exception")
        self.assertIsNone(device.mapping)
        self.assertEqual(
            device.calls.count(("reverse", "--no-rebind", "tcp:8000", "tcp:18080")), 2
        )

    def test_changed_mapping_is_not_removed_or_replaced(self):
        device = MappingDevice()
        with self.assertRaises(RoomFailure), ApiMapping(device, 18080):
            device.mapping = "tcp:9999"
        self.assertEqual(device.mapping, "tcp:9999")
        self.assertNotIn(("reverse", "--remove", "tcp:8000"), device.calls)

    def test_offline_restore_does_not_replace_a_new_mapping(self):
        device = MappingDevice("tcp:18080")
        with (
            self.assertRaises(RoomFailure),
            ApiMapping(device, 18080) as mapping,
            mapping.offline(),
        ):
            device.mapping = "tcp:9999"
        self.assertEqual(device.mapping, "tcp:9999")
        self.assertEqual(
            device.calls.count(("reverse", "--no-rebind", "tcp:8000", "tcp:18080")), 0
        )

    def test_malformed_mapping_list_is_rejected(self):
        device = Mock()
        device.command.return_value = "unknown mapping output"
        with self.assertRaises(RoomFailure), ApiMapping(device, 18080):
            pass
        device.command.assert_called_once_with("reverse", "--list")


class ConfigTest(unittest.TestCase):
    arguments = (
        "--adb",
        "unused-adb",
        "--device",
        "phone-a",
        "--device",
        "phone-b",
        "--device",
        "emulator-5554",
        "--output",
        ".local-tools/room-test-unused",
    )

    def test_requires_three_distinct_explicit_devices(self):
        args = parse_args(self.arguments)
        self.assertEqual(args.device, ["phone-a", "phone-b", "emulator-5554"])
        self.assertEqual(args.host_port, 18080)
        for arguments in (
            self.arguments[:-4] + self.arguments[-2:],
            [*self.arguments, "--device", "extra"],
            [value if value != "phone-b" else "phone-a" for value in self.arguments],
        ):
            with (
                self.subTest(arguments=arguments),
                patch("sys.stderr", new=io.StringIO()),
                self.assertRaises(SystemExit),
            ):
                parse_args(arguments)

    def test_output_must_be_new_and_inside_local_tools(self):
        with patch.object(Path, "exists", return_value=False):
            self.assertEqual(
                validate_output(Path(".local-tools/room-test-unused")),
                ROOT / ".local-tools" / "room-test-unused",
            )
            for path in (
                ROOT,
                ROOT / ".local-tools",
                ROOT / "docs" / "result",
                ROOT / ".." / "result",
            ):
                with self.subTest(path=path), self.assertRaises(RoomFailure):
                    validate_output(path)
        with (
            patch.object(Path, "exists", return_value=True),
            self.assertRaises(RoomFailure),
        ):
            validate_output(ROOT / ".local-tools" / "existing")

    def test_health_requires_production_and_a_bounded_body(self):
        for payload in (
            b'{"status":"ok","mode":"local_test"}',
            b'{"mode":"production"}',
            b"private",
            b"x" * 4097,
        ):
            response = MagicMock(status=200)
            response.read.return_value = payload
            response.__enter__.return_value = response
            opener = Mock()
            opener.open.return_value = response
            with (
                self.subTest(payload=payload),
                patch("rooms.build_opener", return_value=opener),
                self.assertRaises(RoomFailure),
            ):
                health(18080)
            response.read.assert_called_once_with(4097)

    def test_health_uses_only_the_selected_loopback_tunnel(self):
        response = MagicMock(status=200)
        response.read.return_value = b'{"status":"ok","mode":"production"}'
        response.__enter__.return_value = response
        opener = Mock()
        opener.open.return_value = response
        with patch("rooms.build_opener", return_value=opener):
            health(18080)
        opener.open.assert_called_once_with("http://127.0.0.1:18080/healthz", timeout=5)


class DeviceSafetyTest(unittest.TestCase):
    def setUp(self):
        self.device = RoomDevice("unused-adb", "private-serial", "device 1")

    def test_unknown_foreground_prevents_launch(self):
        with (
            patch.object(self.device, "foreground", return_value="another.app"),
            patch.object(self.device, "command") as command,
            self.assertRaises(AssertionError),
        ):
            self.device.launch_product()
        command.assert_not_called()

    def test_saved_room_is_rejected_before_welcome_actions(self):
        self.device.fresh_check = True
        nodes = [
            Element("node", **{"resource-id": "welcome-screen"}),
            Element("node", **{"resource-id": "resume-room-button"}),
        ]
        with (
            patch.object(self.device, "nodes", return_value=nodes),
            patch.object(self.device, "command") as command,
            self.assertRaises(RoomFailure),
        ):
            self.device.find_id("welcome-screen")
        command.assert_not_called()

    def test_leaf_control_is_found_without_scrolling(self):
        node = Element("node", **{"resource-id": "welcome-screen"})
        with (
            patch.object(self.device, "nodes", return_value=[node]),
            patch.object(self.device, "scroll") as scroll,
        ):
            self.assertIs(self.device.find_id("welcome-screen"), node)
        scroll.assert_not_called()

    def test_unknown_saved_input_is_not_changed(self):
        node = Element("node", text="Personal draft", hint="Your name")
        with (
            patch.object(self.device, "find_id", return_value=node),
            patch.object(self.device, "tap_id") as tap,
            patch.object(self.device, "command") as command,
            self.assertRaises(RoomFailure),
        ):
            self.device.fill_id("display-name-input", "Room Host")
        tap.assert_not_called()
        command.assert_not_called()

    def test_matching_test_input_needs_no_edit(self):
        node = Element("node", text="Room Host")
        with (
            patch.object(self.device, "find_id", return_value=node),
            patch.object(self.device, "tap_id") as tap,
            patch.object(self.device, "command") as command,
        ):
            self.device.fill_id("display-name-input", "Room Host")
        tap.assert_not_called()
        command.assert_not_called()

    def test_foreground_is_checked_before_typing_and_back(self):
        node = Element("node", text="Your name", hint="Your name")
        with (
            patch.object(self.device, "find_id", return_value=node),
            patch.object(self.device, "tap_id"),
            patch.object(self.device, "check_foreground") as guard,
            patch.object(self.device, "command") as command,
        ):
            self.device.fill_id("display-name-input", "Room Host")
        self.assertEqual(guard.call_args_list, [call({PRODUCT}), call({PRODUCT})])
        self.assertEqual(
            command.call_args_list,
            [
                call("shell", "input", "text", "Room%sHost"),
                call("shell", "input", "keyevent", "4"),
            ],
        )

    def test_other_saved_server_is_not_changed(self):
        node = Element("node", text="https://private.example.test")
        with (
            patch.object(self.device, "find_id", return_value=node),
            patch.object(self.device, "tap_id") as tap,
            patch.object(self.device, "command") as command,
            self.assertRaises(RoomFailure),
        ):
            self.device.verify_welcome("Room Host")
        tap.assert_called_once_with("server-settings-toggle")
        command.assert_not_called()

    def test_presence_uses_exact_names_and_connected_status(self):
        node = Element(
            "node",
            **{
                "resource-id": "member-test",
                "content-desc": "Room Host, you, host, Ready",
            },
        )
        self.assertTrue(member_present([node], "Room Host", {"Ready"}))
        self.assertFalse(member_present([node], "Host", {"Ready"}))
        self.assertFalse(member_present([node], "Room Host", {"Away"}))

    def test_chat_requires_a_message_node_not_a_draft(self):
        message = Element(
            "node",
            **{"resource-id": "message-test", "content-desc": "You, 12:00: Hello test"},
        )
        draft = Element(
            "node", text="Hello test", **{"resource-id": "chat-message-input"}
        )
        self.assertTrue(message_present([message], "Hello test"))
        self.assertFalse(message_present([draft], "Hello test"))

    def test_control_below_scroll_view_is_scrolled_before_tap(self):
        parent = Element(
            "node",
            **{"class": "android.widget.ScrollView", "bounds": "[0,100][1080,1900]"},
        )
        clipped = Element("node", enabled="true", bounds="[100,1860][900,1970]")
        parent.append(clipped)
        visible = Element("node", enabled="true", bounds="[100,1600][900,1710]")
        self.device.last_nodes = [parent]
        with (
            patch.object(self.device, "find_id", side_effect=[clipped, visible]),
            patch("rooms.system_bar_viewport", return_value=(0, 96, 1080, 2298)),
            patch.object(self.device, "command"),
            patch.object(self.device, "check_foreground") as guard,
            patch.object(self.device, "scroll") as scroll,
            patch.object(self.device, "tap_node") as tap,
        ):
            self.device.tap_id("test-button")
        scroll.assert_called_once_with(down=True, small=True)
        guard.assert_called_once_with({PRODUCT})
        tap.assert_called_once_with(visible)

    def test_expired_run_does_not_read_or_interact_with_ui(self):
        self.device.deadline = 0
        with (
            patch.object(self.device, "nodes") as nodes,
            self.assertRaises(RoomFailure),
        ):
            self.device.find_id("welcome-screen")
        nodes.assert_not_called()

    def test_clipped_leave_header_still_needs_a_safe_viewport_before_tapping(self):
        clipped = Element("node", enabled="true", bounds="[750,0][980,94]")
        visible = Element("node", enabled="true", bounds="[750,128][980,222]")
        with (
            patch.object(self.device, "find_id", side_effect=[clipped, visible]),
            patch("rooms.system_bar_viewport", return_value=(0, 96, 1080, 2298)),
            patch.object(self.device, "command"),
            patch.object(self.device, "check_foreground") as guard,
            patch.object(self.device, "scroll") as scroll,
            patch.object(self.device, "tap_node") as tap,
        ):
            self.device.tap_id("leave-room-button")
        scroll.assert_called_once_with(down=False, small=True)
        guard.assert_called_once_with({PRODUCT})
        tap.assert_called_once_with(visible)

    def test_leave_confirms_the_visible_product_dialog_before_waiting_for_welcome(self):
        button = Element(
            "node",
            **{
                "package": PRODUCT,
                "resource-id": "android:id/button1",
                "text": "LEAVE ROOM",
                "enabled": "true",
                "bounds": "[500,1200][900,1400]",
            },
        )
        nodes = [
            Element("node", text="Leave this room?"),
            Element("node", text="You can return with the room code."),
            button,
        ]
        with (
            patch.object(self.device, "tap_id") as tap,
            patch.object(self.device, "nodes", return_value=nodes),
            patch.object(self.device, "find_id") as find,
            patch.object(self.device, "check_foreground") as guard,
            patch.object(self.device, "command") as command,
            patch("rooms.system_bar_viewport", return_value=(0, 96, 1080, 2298)),
        ):
            self.device.leave()
        tap.assert_called_once_with("leave-room-button")
        guard.assert_called_once_with({PRODUCT})
        command.assert_has_calls(
            [
                call("shell", "dumpsys", "window", "displays"),
                call("shell", "input", "tap", "700", "1300"),
            ]
        )
        find.assert_called_once_with("welcome-screen")

    def test_leave_does_not_confirm_an_unrecognized_dialog(self):
        button = Element(
            "node",
            **{
                "package": PRODUCT,
                "resource-id": "android:id/button1",
                "text": "LEAVE ROOM",
                "enabled": "true",
                "bounds": "[500,1200][900,1400]",
            },
        )
        with (
            patch.object(self.device, "tap_id"),
            patch.object(self.device, "nodes", return_value=[button]),
            patch.object(self.device, "command") as command,
            patch("rooms.time.sleep"),
            self.assertRaises(RoomFailure),
        ):
            self.device.leave()
        command.assert_not_called()


class ScenarioTest(unittest.TestCase):
    def test_leave_control_search_starts_toward_the_header(self):
        device = RoomDevice("adb", "test-device", "test")
        button = Element("node", **{"resource-id": "leave-room-button"})
        with (
            patch.object(device, "nodes", side_effect=[[], [button]]),
            patch.object(device, "check_foreground"),
            patch.object(device, "scroll") as scroll,
            patch("rooms.time.sleep"),
        ):
            self.assertIs(device.find_id("leave-room-button"), button)
        scroll.assert_called_once_with(False)

    def test_distant_leave_header_search_never_reverses_downward(self):
        device = RoomDevice("adb", "test-device", "test")
        button = Element("node", **{"resource-id": "leave-room-button"})
        position = 6

        def scroll(down):
            nonlocal position
            position += 1 if down else -1

        with (
            patch.object(
                device,
                "nodes",
                side_effect=lambda unused: [button] if position == 0 else [],
            ),
            patch.object(device, "check_foreground") as guard,
            patch.object(device, "scroll", side_effect=scroll) as swipe,
            patch("rooms.time.sleep"),
        ):
            self.assertIs(device.find_id("leave-room-button"), button)
        self.assertEqual(swipe.call_args_list, [call(False)] * 6)
        self.assertEqual(guard.call_args_list, [call({PRODUCT})] * 6)

    def test_missing_leave_header_search_is_bounded_and_upward_only(self):
        device = RoomDevice("adb", "test-device", "test")
        with (
            patch.object(device, "nodes", return_value=[]) as nodes,
            patch.object(device, "check_foreground") as guard,
            patch.object(device, "scroll") as scroll,
            patch("rooms.time.sleep"),
            self.assertRaises(RoomFailure),
        ):
            device.find_id("leave-room-button")
        self.assertEqual(nodes.call_count, 12)
        self.assertEqual(scroll.call_args_list, [call(False)] * 12)
        self.assertEqual(guard.call_args_list, [call({PRODUCT})] * 12)

    def test_non_header_search_keeps_the_existing_bidirectional_scan(self):
        device = RoomDevice("adb", "test-device", "test")
        button = Element("node", **{"resource-id": "ready-toggle-button"})
        with (
            patch.object(device, "nodes", side_effect=[[], [], [], [], [], [button]]),
            patch.object(device, "check_foreground"),
            patch.object(device, "scroll") as scroll,
            patch("rooms.time.sleep"),
        ):
            self.assertIs(device.find_id("ready-toggle-button"), button)
        self.assertEqual(scroll.call_args_list, [call(True)] * 4 + [call(False)])

    def devices(self):
        devices = [Mock() for _ in range(3)]
        devices[0].find_id.return_value = Element("node", text="0123456789AB")
        return devices

    def test_full_sequence_uses_every_device_and_leaves_guests_first(self):
        devices = self.devices()
        mapping_devices = [MappingDevice("tcp:18080") for _ in devices]
        mappings = [ApiMapping(device, 18080) for device in mapping_devices]
        passed = Mock()
        order = []
        for index, device in enumerate(devices):
            device.leave.side_effect = lambda index=index: order.append(index)
        exercise(devices, mappings, passed)
        self.assertEqual(order, [2, 1, 0])
        self.assertEqual(passed.call_count, 8)
        for device in devices:
            device.tap_id.assert_any_call("ready-toggle-button")
            device.members.assert_any_call({"Ready"})
            self.assertEqual(device.send.call_count, 2 if device is devices[0] else 1)
            device.message.assert_any_call("Hello from friend B")
        devices[1].command.assert_called_once_with("shell", "input", "keyevent", "3")
        devices[0].stop_product.assert_called_once()
        devices[2].stop_product.assert_called_once()
        devices[2].tap_id.assert_any_call("resume-room-button")
        self.assertEqual(mapping_devices[2].mapping, "tcp:18080")

    def test_offline_ui_failure_restores_mapping_without_retry_or_leave(self):
        devices = self.devices()
        devices[2].find_id.side_effect = RoomFailure("expected_ui_state_missing")
        mapping_devices = [MappingDevice("tcp:18080") for _ in devices]
        mappings = [ApiMapping(device, 18080) for device in mapping_devices]
        with self.assertRaises(RoomFailure):
            exercise(devices, mappings, Mock())
        self.assertEqual(mapping_devices[2].mapping, "tcp:18080")
        self.assertNotIn(call("resume-room-button"), devices[2].tap_id.call_args_list)
        for device in devices:
            device.leave.assert_not_called()


class FailureCleanupTest(unittest.TestCase):
    def test_scenario_failure_closes_every_mapping_and_redacts_result(self):
        args = argparse.Namespace(
            adb="unused",
            device=["private-a", "private-b", "private-c"],
            host_port=18080,
            output=Path(".local-tools/room-test-unused"),
        )
        devices = [Mock(label=f"device {index + 1}") for index in range(3)]
        events = []

        @contextmanager
        def mapping(device, unused_port):
            events.append(("open", device.label))
            try:
                yield Mock()
            finally:
                events.append(("close", device.label))

        with (
            patch("rooms.parse_args", return_value=args),
            patch("rooms.prepare_output", return_value=Mock()),
            patch("rooms.RoomDevice", side_effect=devices),
            patch("rooms.health"),
            patch("rooms.ApiMapping", side_effect=mapping),
            patch(
                "rooms.exercise",
                side_effect=RuntimeError("secret invite token private-a"),
            ),
            patch("rooms.write_result") as write,
            patch("builtins.print"),
        ):
            self.assertEqual(main(), 1)
        self.assertEqual(
            events[-3:],
            [("close", "device 3"), ("close", "device 2"), ("close", "device 1")],
        )
        report = write.call_args.args[1]
        raw = json.dumps(report)
        for private in (
            "secret",
            "invite token",
            "private-a",
            "private-b",
            "private-c",
        ):
            self.assertNotIn(private, raw)
        self.assertEqual(report["checks"][-1]["result"], "failed")


if __name__ == "__main__":
    unittest.main()
