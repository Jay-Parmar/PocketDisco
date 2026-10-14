import json
import unittest
from datetime import datetime, timedelta
from unittest.mock import Mock, patch
from xml.etree.ElementTree import Element

from run import Device, Forward, LAB, LAUNCHERS, MUSIC, PORT, SOURCE, SYSTEM, audio_proof, bounds, passed_check, silence_proof, system_bar_viewport, utc_now


DISPLAY_LAYOUTS = ((1080, 2340, 96, 2298), (1084, 2412, 126, 2349), (1080, 2400, 128, 2337))


def display_dump(width, height, top, bottom):
    return (f"  mDisplayFrame=Rect(0, 0 - {width}, {height})\n"
            f"    InsetsSource id=1 type=statusBars frame=[0,0][{width},{top}] visible=true flags=\n"
            f"    InsetsSource id=2 type=navigationBars frame=[0,{bottom}][{width},{height}] visible=true flags=\n")


class ViewportTest(unittest.TestCase):
    def test_verified_device_layouts(self):
        for width, height, top, bottom in DISPLAY_LAYOUTS:
            with self.subTest(width=width, height=height):
                self.assertEqual(system_bar_viewport(display_dump(width, height, top, bottom)),
                                 (0, top, width, bottom))

    def test_unrelated_insets_do_not_change_the_system_bar_viewport(self):
        raw = display_dump(*DISPLAY_LAYOUTS[2])
        raw += "    InsetsSource id=3 type=ime frame=[0,0][0,0] visible=false flags=\n"
        self.assertEqual(system_bar_viewport(raw), (0, 128, 1080, 2337))

    def test_provider_and_control_references_are_not_extra_state_rows(self):
        raw = display_dump(*DISPLAY_LAYOUTS[2])
        providers = "\n".join("    mSource=" + line.strip() for line in raw.splitlines()[1:])
        controls = "\n    mControl=InsetsSourceControl id=1 type=statusBars\n"
        self.assertEqual(system_bar_viewport(raw + providers + controls), (0, 128, 1080, 2337))

    def test_missing_or_unrecognized_geometry_is_rejected_without_raw_output(self):
        raw = display_dump(*DISPLAY_LAYOUTS[2])
        cases = ("", raw.splitlines()[0], "\n".join(raw.splitlines()[1:]),
                 raw.replace("visible=true", "visible=false"), raw.replace("visible=true", "visible=unknown"),
                 raw.replace("InsetsSource id=1", "UnknownSource id=1"),
                 raw.replace("Rect(0, 0 - 1080, 2400)", "Rect(0, 0 - unknown, 2400)"))
        for malformed in cases:
            with self.subTest(raw=malformed), self.assertRaises(RuntimeError) as error:
                system_bar_viewport(malformed + "\nprivate unrelated window information")
            self.assertEqual(str(error.exception), "Cannot verify the display's system-bar viewport")

    def test_ambiguous_frames_and_sources_are_rejected(self):
        raw = display_dump(*DISPLAY_LAYOUTS[2])
        cases = (raw + raw.splitlines()[0], raw + raw.splitlines()[1], raw + display_dump(*DISPLAY_LAYOUTS[0]),
                 raw.replace("type=navigationBars", "type=statusBars"), raw.replace("id=2", "id=1"))
        for ambiguous in cases:
            with self.subTest(raw=ambiguous), self.assertRaises(RuntimeError):
                system_bar_viewport(ambiguous)

    def test_bars_must_be_positive_full_edges_within_the_display(self):
        raw = display_dump(*DISPLAY_LAYOUTS[2])
        cases = (raw.replace("Rect(0, 0", "Rect(1, 0"), raw.replace("Rect(0, 0", "Rect(0, -1"),
                 raw.replace("1080, 2400)", "0, 2400)"),
                 raw.replace("[0,0][1080,128]", "[-1,0][1080,128]"),
                 raw.replace("[0,0][1080,128]", "[0,0][1079,128]"),
                 raw.replace("[0,0][1080,128]", "[0,10][1080,128]"),
                 raw.replace("[0,0][1080,128]", "[0,0][1080,0]"),
                 raw.replace("[0,0][1080,128]", "[0,0][1080,2400]"),
                 raw.replace("[0,2337][1080,2400]", "[0,2337][1080,2401]"),
                 raw.replace("[0,2337][1080,2400]", "[0,0][1080,63]"),
                 raw.replace("[0,2337][1080,2400]", "[0,100][1080,2400]"))
        for malformed in cases:
            with self.subTest(raw=malformed), self.assertRaises(RuntimeError):
                system_bar_viewport(malformed)


class HelpersTest(unittest.TestCase):
    def test_recognizes_the_selected_miui_phone_launcher(self):
        device = Device("adb", "private-device", "phone")
        with patch.object(device, "foreground", return_value="com.mi.android.globallauncher"):
            device.check_foreground(LAUNCHERS)
        with patch.object(device, "foreground", return_value="com.mi.android.globallauncher.other"):
            with self.assertRaises(AssertionError):
                device.check_foreground(LAUNCHERS)

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

    def test_named_system_dialog_uses_its_actual_window_owner(self):
        device = Device("adb", "test", "phone")
        focus = "mCurrentFocus=Window{abc u0 Stop sharing?}"
        windows = ("  Window #0 Window{abc u0 Stop sharing?}:\n"
                   "    mOwnerUid=10185 showForAllUsers=true package=com.android.systemui appop=NONE\n"
                   "  Window #1 Window{def u0 Other}:\n"
                   "    mOwnerUid=10001 package=other.app\n")
        with patch.object(device, "command", side_effect=[focus, windows]):
            self.assertEqual(device.foreground(), "com.android.systemui")

    def test_window_title_cannot_impersonate_a_system_package(self):
        device = Device("adb", "test", "phone")
        focus = "mCurrentFocus=Window{abc u0 com.android.systemui}"
        windows = ("  Window #0 Window{abc u0 com.android.systemui}:\n"
                   "    mOwnerUid=10001 package=other.app\n")
        with patch.object(device, "command", side_effect=[focus, windows]):
            self.assertEqual(device.foreground(), "other.app")

    def test_missing_focused_window_does_not_use_another_owner(self):
        device = Device("adb", "test", "phone")
        focus = "mCurrentFocus=Window{abc u0 Stop sharing?}"
        windows = ("  Window #0 Window{def u0 Stop sharing?}:\n"
                   "    mOwnerUid=10185 package=com.android.systemui\n")
        with patch.object(device, "command", side_effect=[focus, windows]):
            self.assertEqual(device.foreground(), "")

    def test_empty_focus_gets_a_bounded_transition_retry(self):
        device = Device("adb", "test", "phone")
        with patch.object(device, "foreground", side_effect=["", "", LAB]), patch("run.time.sleep"):
            device.check_foreground({LAB})

    def test_persistent_empty_focus_stops_the_test(self):
        device = Device("adb", "test", "phone")
        with patch.object(device, "foreground", return_value="") as foreground, patch("run.time.sleep"):
            with self.assertRaisesRegex(AssertionError, "focus: none"):
                device.check_foreground({LAB})
        self.assertEqual(foreground.call_count, 10)

    def test_foreground_failure_identifies_only_known_packages(self):
        device = Device("adb", "test", "phone")
        for package in {LAB, SOURCE, MUSIC, *SYSTEM, *LAUNCHERS}:
            with self.subTest(package=package), patch.object(device, "foreground", return_value=package):
                with self.assertRaises(AssertionError) as error:
                    device.check_foreground(set())
                self.assertIn(f"focus: {package}", str(error.exception))

    def test_foreground_failure_redacts_unknown_package(self):
        device = Device("adb", "test", "phone")
        with patch.object(device, "foreground", return_value="private.other.app"):
            with self.assertRaises(AssertionError) as error:
                device.check_foreground({LAB})
        self.assertIn("focus: another app", str(error.exception))
        self.assertNotIn("private.other.app", str(error.exception))

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


class TapTest(unittest.TestCase):
    def setUp(self):
        self.device = Device("adb", "test", "phone")
        self.display = display_dump(*DISPLAY_LAYOUTS[0])
        self.visible = Element("node", package=LAB, text="Host USB/TCP test", bounds="[106,980][529,1098]")

    def test_scrolls_top_clipped_control_before_tapping(self):
        clipped = Element("node", package=LAB, text="Host USB/TCP test", bounds="[106,0][529,98]")
        with patch.object(self.device, "find", side_effect=[clipped, self.visible]) as find, \
                patch.object(self.device, "command", return_value=self.display), \
                patch.object(self.device, "check_foreground") as guard, \
                patch.object(self.device, "scroll") as scroll, patch.object(self.device, "tap_node") as tap:
            self.device.tap("Host USB/TCP test")
        scroll.assert_called_once_with(down=False, small=True)
        guard.assert_called_once_with({LAB})
        self.assertEqual(find.call_count, 2)
        tap.assert_called_once_with(self.visible)

    def test_scrolls_bottom_clipped_control_before_tapping(self):
        clipped = Element("node", package=LAB, text="Host USB/TCP test", bounds="[106,2302][529,2400]")
        with patch.object(self.device, "find", side_effect=[clipped, self.visible]), \
                patch.object(self.device, "command", return_value=self.display), \
                patch.object(self.device, "check_foreground"), \
                patch.object(self.device, "scroll") as scroll, patch.object(self.device, "tap_node") as tap:
            self.device.tap("Host USB/TCP test")
        scroll.assert_called_once_with(down=True, small=True)
        tap.assert_called_once_with(self.visible)

    def test_visible_control_uses_current_bounds_without_scrolling(self):
        with patch.object(self.device, "find", return_value=self.visible), \
                patch.object(self.device, "command", return_value=self.display) as command, \
                patch.object(self.device, "check_foreground") as guard, \
                patch.object(self.device, "scroll") as scroll:
            self.device.tap("Host USB/TCP test")
        scroll.assert_not_called()
        guard.assert_called_once_with({LAB})
        command.assert_any_call("shell", "dumpsys", "window", "displays")
        command.assert_called_with("shell", "input", "tap", "317", "1039")

    def test_nothing_source_file_button_needs_no_scroll(self):
        title = "Open generated file in music app"
        node = Element("node", package=SOURCE, text=title, bounds="[99,1995][985,2142]")
        with patch.object(self.device, "find", return_value=node) as find, \
                patch.object(self.device, "command", return_value=display_dump(*DISPLAY_LAYOUTS[1])) as command, \
                patch.object(self.device, "check_foreground") as guard, \
                patch.object(self.device, "scroll") as scroll:
            self.device.tap(title, SOURCE)
        find.assert_called_once_with(title, SOURCE)
        scroll.assert_not_called()
        guard.assert_called_once_with({SOURCE})
        command.assert_called_with("shell", "input", "tap", "542", "2068")

    def test_fully_visible_last_button_needs_no_scroll_on_each_layout(self):
        for width, height, top, bottom in DISPLAY_LAYOUTS:
            with self.subTest(width=width, height=height):
                node = Element("node", package=LAB, text="Show details",
                               bounds=f"[100,{bottom - 140}][{width - 100},{bottom - 8}]")
                dump = display_dump(width, height, top, bottom)

                with patch.object(self.device, "find", return_value=node), \
                        patch.object(self.device, "command", return_value=dump), \
                        patch.object(self.device, "check_foreground"), \
                        patch.object(self.device, "scroll") as scroll, patch.object(self.device, "tap_node") as tap:
                    self.device.tap("Show details")
                scroll.assert_not_called()
                tap.assert_called_once_with(node)

    def test_clipped_control_has_bounded_scroll_attempts(self):
        clipped = Element("node", package=LAB, text="Host USB/TCP test", bounds="[106,0][529,98]")
        with patch.object(self.device, "find", return_value=clipped) as find, \
                patch.object(self.device, "command", return_value=self.display), \
                patch.object(self.device, "check_foreground") as guard, \
                patch.object(self.device, "scroll") as scroll, patch.object(self.device, "tap_node") as tap:
            with self.assertRaisesRegex(AssertionError, "control stayed near the screen edge"):
                self.device.tap("Host USB/TCP test")
        self.assertEqual(find.call_count, 8)
        self.assertEqual(scroll.call_count, 7)
        self.assertEqual(guard.call_count, 7)
        tap.assert_not_called()

    def test_foreground_change_blocks_corrective_scroll(self):
        clipped = Element("node", package=SOURCE, text="Play allowed", bounds="[106,0][529,98]")
        with patch.object(self.device, "find", return_value=clipped) as find, \
                patch.object(self.device, "command", return_value=self.display), \
                patch.object(self.device, "check_foreground", side_effect=AssertionError("unrelated app")) as guard, \
                patch.object(self.device, "scroll") as scroll, patch.object(self.device, "tap_node") as tap:
            with self.assertRaisesRegex(AssertionError, "unrelated app"):
                self.device.tap("Play allowed", SOURCE)
        find.assert_called_once_with("Play allowed", SOURCE)
        guard.assert_called_once_with({SOURCE})
        scroll.assert_not_called()
        tap.assert_not_called()

    def test_unverified_viewport_stops_before_finding_or_tapping(self):
        with patch.object(self.device, "command", return_value="private unrelated information"), \
                patch.object(self.device, "find") as find, patch.object(self.device, "scroll") as scroll, \
                patch.object(self.device, "tap_node") as tap:
            with self.assertRaisesRegex(RuntimeError, "Cannot verify"):
                self.device.tap("Show details")
        find.assert_not_called()
        scroll.assert_not_called()
        tap.assert_not_called()

    def test_horizontal_clipping_stops_without_a_vertical_scroll(self):
        node = Element("node", package=LAB, text="Show details", bounds="[100,1000][1100,1140]")
        with patch.object(self.device, "command", return_value=self.display), \
                patch.object(self.device, "find", return_value=node), patch.object(self.device, "scroll") as scroll, \
                patch.object(self.device, "tap_node") as tap:
            with self.assertRaisesRegex(AssertionError, "outside the horizontal viewport"):
                self.device.tap("Show details")
        scroll.assert_not_called()
        tap.assert_not_called()


class ScrollTest(unittest.TestCase):
    def setUp(self):
        self.device = Device("adb", "test", "phone")

    def test_small_scroll_is_short_and_slow(self):
        with patch.object(self.device, "command", return_value="Physical size: 1080x2400") as command:
            self.device.scroll(small=True)
        command.assert_called_with("shell", "input", "swipe", "540", "1440", "540", "960", "700")

    def test_small_upward_scroll_reverses_the_same_gesture(self):
        with patch.object(self.device, "command", return_value="Physical size: 1080x2400") as command:
            self.device.scroll(down=False, small=True)
        command.assert_called_with("shell", "input", "swipe", "540", "960", "540", "1440", "700")

    def test_search_scroll_keeps_its_original_distance_and_duration(self):
        with patch.object(self.device, "command", return_value="Physical size: 1080x2400") as command:
            self.device.scroll()
        command.assert_called_with("shell", "input", "swipe", "540", "1800", "540", "600", "350")

    def test_search_upward_scroll_keeps_its_original_distance_and_duration(self):
        with patch.object(self.device, "command", return_value="Physical size: 1080x2400") as command:
            self.device.scroll(down=False)
        command.assert_called_with("shell", "input", "swipe", "540", "600", "540", "1800", "350")


class EvidenceTest(unittest.TestCase):
    def test_passed_check_preserves_observations_after_reconnect(self):
        phone, emulator = Device("adb", "host", "phone"), Device("adb", "peer", "emulator")
        phone.latest = {"sentPcm": {"first100AudibleFramesSha256": "matching", "frames": 100}}
        emulator.latest = {"receivedPcm": {"first100AudibleFramesSha256": "matching", "frames": 100}}
        with patch("run.utc_now", return_value="2026-10-12T10:00:00Z"):
            check = passed_check("matching PCM", phone, emulator)
        phone.latest["sentPcm"]["frames"] = 200
        emulator.latest["receivedPcm"]["first100AudibleFramesSha256"] = "reset"
        phone.latest = {"active": False}
        emulator.latest = {"receivedPcm": {"first100AudibleFramesSha256": "new session", "frames": 10}}
        self.assertEqual(check, {
            "name": "matching PCM",
            "result": "passed",
            "recordedAtUtc": "2026-10-12T10:00:00Z",
            "latestObservations": {
                "phone": {"sentPcm": {"first100AudibleFramesSha256": "matching", "frames": 100}},
                "emulator": {"receivedPcm": {"first100AudibleFramesSha256": "matching", "frames": 100}},
            },
        })
        self.assertEqual(json.loads(json.dumps(check)), check)

    def test_passed_check_does_not_read_devices(self):
        phone, emulator = Device("adb", "host", "phone"), Device("adb", "peer", "emulator")
        with patch.object(Device, "report") as report, patch.object(Device, "command") as command:
            check = passed_check("no recent observations", phone, emulator)
        self.assertEqual(check["latestObservations"], {"phone": {}, "emulator": {}})
        report.assert_not_called()
        command.assert_not_called()

    def test_timestamps_are_explicit_utc(self):
        value = utc_now()
        self.assertTrue(value.endswith("Z"))
        self.assertEqual(datetime.fromisoformat(value.replace("Z", "+00:00")).utcoffset(), timedelta(0))


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


class SilenceTest(unittest.TestCase):
    def check_samples(self, before, after, require_zero=False):
        phone = Mock()
        phone.report.side_effect = [{"capture": before}, {"capture": after}]
        with patch("run.time.sleep"):
            return silence_proof(phone, require_zero)

    def test_silence_needs_live_capture_progress(self):
        before = {"running": True, "frames": 100, "nonSilentFrames": 20}
        after = {**before, "frames": 300}
        self.assertEqual(self.check_samples(before, after), after)
        for bad in (before, {**after, "running": False}, {**after, "nonSilentFrames": 21}):
            with self.assertRaises(AssertionError):
                self.check_samples(before, bad)

    def test_uid_isolation_requires_no_prior_signal(self):
        before = {"running": True, "frames": 100, "nonSilentFrames": 0}
        self.check_samples(before, {**before, "frames": 300}, require_zero=True)
        before["nonSilentFrames"] = 1
        with self.assertRaises(AssertionError):
            self.check_samples(before, {**before, "frames": 300}, require_zero=True)


if __name__ == "__main__":
    unittest.main()
