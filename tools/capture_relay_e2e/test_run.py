import json
import unittest
from unittest.mock import Mock, patch
from xml.etree.ElementTree import Element

from run import Device, Forward, LAB, PORT, audio_proof, bounds


class HelpersTest(unittest.TestCase):
    def test_bounds_reject_clipped_nodes(self):
        for value in ("", "[0,0]", "[2,2][2,8]", "[2,5][8,3]"):
            self.assertIsNone(bounds(Element("node", bounds=value)))
        self.assertEqual(bounds(Element("node", bounds="[1,2][3,4]")), [1, 2, 3, 4])

    def test_fill_replaces_code_without_logging_it(self):
        device = Device("adb", "test", "phone")
        node = Element("node", text="a" * 32, bounds="[1,2][3,4]")
        with patch.object(device, "find", return_value=node), patch.object(device, "command") as command:
            device.fill_code("b" * 32)
        self.assertIn(unittest.mock.call("shell", "input", "keyevent", "123", *(["67"] * 32)), command.call_args_list)
        with self.assertRaises(ValueError):
            device.fill_code("bad")

    def test_adb_failure_does_not_expose_command_or_stderr(self):
        device = Device("adb", "test", "phone")
        result = Mock(returncode=1, stdout="private", stderr="private")
        with patch("run.subprocess.run", return_value=result), self.assertRaisesRegex(RuntimeError, "^phone: ADB shell failed$"):
            device.command("shell", "input", "text", "secret")

    def test_adb_text_uses_utf8_on_windows(self):
        device = Device("adb", "test", "phone")
        result = Mock(returncode=0, stdout="PocketDisco Capture L\u2026", stderr="")
        with patch("run.subprocess.run", return_value=result) as command:
            self.assertEqual(device.command("shell", "example"), result.stdout)
        self.assertEqual(command.call_args.kwargs["encoding"], "utf-8")

    def test_report_ignores_unknown_fields(self):
        device = Device("adb", "test", "phone")
        with patch.object(device, "command", return_value=json.dumps({"active": True, "sessionCode": "secret"})):
            self.assertEqual(device.report(), {"active": True})

    def test_unexpected_foreground_blocks_ui_dump(self):
        device = Device("adb", "test", "phone")
        with patch.object(device, "foreground", return_value="other.app"), patch.object(device, "command") as command:
            with self.assertRaises(AssertionError):
                device.nodes()
            command.assert_not_called()

    def test_foreground_uses_complete_window_dump(self):
        device = Device("adb", "test", "phone")
        window = f"mCurrentFocus=Window{{12a u0 {LAB}/.MainActivity}}"
        with patch.object(device, "command", return_value=window) as command:
            self.assertEqual(device.foreground(), LAB)
        command.assert_called_once_with("shell", "dumpsys", "window")

    def test_empty_focus_gets_a_bounded_transition_retry(self):
        device = Device("adb", "test", "phone")
        with patch.object(device, "foreground", side_effect=["", "", LAB]), patch("run.time.sleep"):
            device.check_foreground({LAB})

    def test_persistent_empty_focus_stops_the_test(self):
        device = Device("adb", "test", "phone")
        with patch.object(device, "foreground", return_value="") as foreground, patch("run.time.sleep"):
            with self.assertRaises(AssertionError):
                device.check_foreground({LAB})
        self.assertEqual(foreground.call_count, 10)

    def test_waits_for_only_the_expected_activity_transition(self):
        device = Device("adb", "test", "phone")
        with patch.object(device, "foreground", side_effect=["com.android.systemui", "", LAB]), patch("run.time.sleep"):
            device.wait_foreground(LAB, {"com.android.systemui"})
        with patch.object(device, "foreground", return_value="other.app"), self.assertRaisesRegex(AssertionError, "unrelated"):
            device.wait_foreground(LAB, {"com.android.systemui"})

    def test_launch_uses_launcher_flags_and_waits_for_focus(self):
        device = Device("adb", "test", "phone")
        with patch.object(device, "command") as command, patch.object(device, "wait_foreground") as wait:
            device.launch()
        args = command.call_args.args
        self.assertIn("android.intent.category.LAUNCHER", args)
        self.assertIn("0x10200000", args)
        wait.assert_called_once()
        self.assertEqual(wait.call_args.args[0], LAB)

    def test_cancel_projection_after_lab_permissions(self):
        device = Device("adb", "test", "phone")
        permission = "com.android.permissioncontroller"
        dialog = [Element("node", package=permission, text="Allow PocketDisco Capture Lab to record audio?")]
        allow = Element("node", package=permission, **{"resource-id": permission + ":id/permission_allow_foreground_only_button"})
        cancel = Element("node", package="com.android.systemui", text="Cancel", enabled="true")
        title = Element("node", package="com.android.systemui", text="Share your screen with PocketDisco Capture L\u2026?")
        with patch.object(device, "nodes", side_effect=[dialog + [allow], [title, cancel]]), patch.object(device, "tap_node") as tap, patch.object(device, "wait_foreground") as wait, patch("run.time.sleep"):
            device.consent(False)
        self.assertEqual(tap.call_args_list, [unittest.mock.call(allow), unittest.mock.call(cancel)])
        self.assertEqual(wait.call_args.args[0], LAB)

    def test_other_permission_prompt_is_not_accepted(self):
        device = Device("adb", "test", "phone")
        dialog = [Element("node", package="com.android.permissioncontroller", text="Other app permission")]
        with patch.object(device, "nodes", return_value=dialog), patch.object(device, "tap_node") as tap:
            with self.assertRaises(AssertionError):
                device.consent()
            tap.assert_not_called()

    def test_other_projection_prompt_is_not_accepted(self):
        device = Device("adb", "test", "phone")
        title = Element("node", package="com.android.systemui", text="Share your screen with another app?")
        share = Element("node", package="com.android.systemui", text="Share screen", enabled="true")
        with patch.object(device, "nodes", return_value=[title, share]), patch.object(device, "tap_node") as tap:
            with self.assertRaisesRegex(AssertionError, "does not identify"):
                device.consent()
        tap.assert_not_called()

    def test_cleanup_dismisses_only_the_lab_projection(self):
        device = Device("adb", "test", "phone")
        title = Element("node", package="com.android.systemui", text="Share your screen with PocketDisco Capture Lab?")
        cancel = Element("node", package="com.android.systemui", text="Cancel", enabled="true")
        with patch.object(device, "foreground", return_value="com.android.systemui"), patch.object(device, "nodes", return_value=[title, cancel]), patch.object(device, "tap_node") as tap:
            device.dismiss_projection()
        tap.assert_called_once_with(cancel)

    def test_cleanup_leaves_another_system_dialog_untouched(self):
        device = Device("adb", "test", "phone")
        cancel = Element("node", package="com.android.systemui", text="Cancel", enabled="true")
        with patch.object(device, "foreground", return_value="com.android.systemui"), patch.object(device, "nodes", return_value=[cancel]), patch.object(device, "tap_node") as tap:
            device.dismiss_projection()
        tap.assert_not_called()


class ForwardTest(unittest.TestCase):
    def test_conflict_is_not_replaced(self):
        device = Mock(serial="phone")
        device.command.return_value = f"other {PORT} tcp:42\n"
        with self.assertRaises(RuntimeError):
            with Forward(device):
                pass
        device.command.assert_called_once_with("forward", "--list")

    def test_own_mapping_removed_even_on_test_failure(self):
        device = Mock(serial="phone")
        device.command.side_effect = ["", "", f"phone {PORT} {PORT}\n", ""]
        with self.assertRaises(ValueError):
            with Forward(device):
                raise ValueError("test failed")
        device.command.assert_any_call("forward", "--no-rebind", PORT, PORT)
        device.command.assert_called_with("forward", "--remove", PORT)

    def test_changed_mapping_is_left_alone(self):
        device = Mock(serial="phone")
        device.command.side_effect = ["", "", f"other {PORT} tcp:42\n"]
        with Forward(device):
            pass
        self.assertEqual(device.command.call_count, 3)

    def test_proof_requires_matching_hashes(self):
        host, peer = Mock(), Mock()
        host.wait_report.return_value = {"sentPcm": {"first100AudibleFramesSha256": "a"}}
        peer.wait_report.return_value = {"receivedPcm": {"first100AudibleFramesSha256": "b"}, "output": {"playbackHeadFrames": 1}}
        with self.assertRaisesRegex(AssertionError, "hashes differ"):
            audio_proof(host, peer)


if __name__ == "__main__":
    unittest.main()
