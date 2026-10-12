import unittest
from unittest.mock import Mock, call

from copy import deepcopy

from sync_run import LAB, SOURCE, QuietPlayback, REVERSE_PORT, Reverse, advancing_timing, stop_devices, timing_proof


class CleanupTest(unittest.TestCase):
    def test_dialog_failure_does_not_skip_owned_app_cleanup(self):
        device = Mock(label="receiver")
        device.dismiss_projection.side_effect = RuntimeError("Unavailable")
        checks = []
        stop_devices([device], None, False, checks)
        self.assertEqual(device.command.call_args_list, [call("shell", "am", "force-stop", LAB),
                                                       call("shell", "am", "force-stop", SOURCE)])
        self.assertEqual(len(checks), 1)

    def test_each_package_gets_an_independent_cleanup_attempt(self):
        device = Mock(label="receiver")
        device.command.side_effect = [RuntimeError("Unavailable"), ""]
        checks = []
        stop_devices([device], None, False, checks)
        self.assertEqual(device.command.call_count, 2)
        self.assertEqual(device.command.call_args, call("shell", "am", "force-stop", SOURCE))


class QuietPlaybackTest(unittest.TestCase):
    def test_lowers_and_restores_unchanged_test_volume(self):
        device = Mock()
        device.command.side_effect = ["volume is 13 in range [0..16]", "", "volume is 2 in range [0..16]", ""]
        with QuietPlayback(device):
            pass
        self.assertIn(call("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "2"), device.command.call_args_list)
        self.assertEqual(device.command.call_args.args[-2:], ("--set", "13"))

    def test_does_not_overwrite_a_user_volume_change(self):
        device = Mock()
        device.command.side_effect = ["volume is 5 in range [0..15]", "", "volume is 4 in range [0..15]"]
        with QuietPlayback(device):
            pass
        self.assertNotIn(call("shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "5"), device.command.call_args_list)

    def test_keeps_muted_or_low_volume_unchanged(self):
        for value in (0, 1, 2):
            device = Mock()
            device.command.return_value = f"volume is {value} in range [0..15]"
            with QuietPlayback(device):
                pass
            self.assertEqual(device.command.call_count, 1)

    def test_unrecognized_volume_stops_before_changing_it(self):
        device = Mock()
        device.command.return_value = "Unavailable"
        with self.assertRaises(RuntimeError):
            with QuietPlayback(device):
                pass
        self.assertEqual(device.command.call_count, 1)


class ReverseTest(unittest.TestCase):
    def test_rejects_existing_mapping(self):
        device = Mock()
        device.command.return_value = f"UsbFfs {REVERSE_PORT} tcp:12345\n"
        with self.assertRaises(RuntimeError):
            with Reverse(device):
                pass
        self.assertEqual(device.command.call_args_list, [call("reverse", "--list")])

    def test_removes_only_owned_mapping(self):
        device = Mock()
        device.command.side_effect = ["", "", f"UsbFfs {REVERSE_PORT} tcp:18990\n", ""]
        with Reverse(device):
            pass
        self.assertIn(call("reverse", "--no-rebind", REVERSE_PORT, "tcp:18990"), device.command.call_args_list)
        self.assertEqual(device.command.call_args, call("reverse", "--remove", REVERSE_PORT))

    def test_leaves_replaced_mapping_untouched(self):
        device = Mock()
        device.command.side_effect = ["", "", f"UsbFfs {REVERSE_PORT} tcp:12345\n"]
        with Reverse(device):
            pass
        self.assertNotIn(call("reverse", "--remove", REVERSE_PORT), device.command.call_args_list)


class TimingProofTest(unittest.TestCase):
    def setUp(self):
        self.report = {
            "reportSequence": 4,
            "relay": {"clockReady": True, "timedFrames": 50, "clockUncertaintyNanos": 4_000_000},
            "output": {"timingState": "playing", "scheduledStarts": 1, "queuedFrames": 60,
                       "startErrorNanos": 1_000_000, "outputErrorNanos": 4_000_000,
                       "playbackHeadFrames": 16000, "writtenBytes": 64000},
        }

    def test_accepts_scheduled_software_output(self):
        self.assertTrue(timing_proof(self.report))

    def test_repeated_report_cannot_prove_sustained_playback(self):
        previous = deepcopy(self.report)
        self.assertFalse(advancing_timing(self.report, previous))
        self.report["reportSequence"] += 1
        self.assertFalse(advancing_timing(self.report, previous))
        self.report["relay"]["timedFrames"] += 1
        self.assertFalse(advancing_timing(self.report, previous))
        self.report["output"]["writtenBytes"] += 640
        self.assertTrue(advancing_timing(self.report, previous))

    def test_requires_clock_and_deadline_evidence(self):
        for key, value in (("clockReady", False), ("timedFrames", 0), ("clockUncertaintyNanos", None),
                           ("clockUncertaintyNanos", 100_000_001)):
            with self.subTest(key=key):
                previous = self.report["relay"][key]
                self.report["relay"][key] = value
                self.assertFalse(timing_proof(self.report))
                self.report["relay"][key] = previous

    def test_rejects_bad_playout_or_missing_observations(self):
        for key, value in (("timingState", "buffering"), ("queuedFrames", 101), ("startErrorNanos", None),
                           ("startErrorNanos", 20_000_001), ("outputErrorNanos", None), ("outputErrorNanos", 20_000_001),
                           ("playbackHeadFrames", 0), ("scheduledStarts", 0)):
            with self.subTest(key=key):
                previous = self.report["output"][key]
                self.report["output"][key] = value
                self.assertFalse(timing_proof(self.report))
                self.report["output"][key] = previous


if __name__ == "__main__":
    unittest.main()
