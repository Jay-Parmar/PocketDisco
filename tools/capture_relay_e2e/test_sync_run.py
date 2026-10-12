import argparse
import io
import unittest
from unittest.mock import MagicMock, Mock, call, patch

from copy import deepcopy

from sync_run import (
    LAB, SOURCE, QuietPlayback, REVERSE_PORT, Reverse, advancing_source,
    advancing_timing, main, parse_args, read_final_reports, sample_count,
    stop_devices, sustained_timing, timing_proof,
)


class SamplesArgumentTest(unittest.TestCase):
    args = ["--adb", "unused-adb", "--source", "emulator-5554", "--receiver", "phone-a",
            "--receiver", "phone-b", "--output", ".local-tools/unused-sample-test"]

    def test_sample_bounds(self):
        for value in ("8", "180", "600"):
            self.assertEqual(sample_count(value), int(value))
        for value in ("7", "601", "0", "-1", "many", "8.5"):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                sample_count(value)

    def test_default_and_long_generated_tone(self):
        self.assertEqual(parse_args(self.args).samples, 8)
        self.assertEqual(parse_args([*self.args, "--samples", "180"]).samples, 180)
        self.assertEqual(parse_args([*self.args, "--samples", "600"]).samples, 600)

    def test_finite_music_file_cannot_run_a_long_sample_window(self):
        self.assertEqual(parse_args([*self.args, "--music-file"]).samples, 8)
        with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit) as error:
            parse_args([*self.args, "--music-file", "--samples", "9"])
        self.assertEqual(error.exception.code, 2)


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

    def test_final_reports_refresh_source_errors_without_ui_actions(self):
        source = Mock(label="source", latest={"status": "Peer connected"})
        source.report.return_value = {"status": "Peer write timeout", "relay": {"peers": 0}}
        receiver = Mock(label="receiver", latest={})
        receiver.report.return_value = {"status": "Peer disconnected"}
        checks = []
        read_final_reports([source, receiver], checks)
        self.assertEqual(source.latest["status"], "Peer write timeout")
        self.assertEqual(receiver.latest["status"], "Peer disconnected")
        source.report.return_value["status"] = "mutated"
        self.assertEqual(source.latest["status"], "Peer write timeout")
        source.command.assert_not_called()
        source.dismiss_projection.assert_not_called()
        self.assertEqual(checks, [])

    def test_failed_final_report_keeps_previous_evidence_and_reads_other_devices(self):
        source = Mock(label="source", latest={"status": "Last observation"})
        source.report.side_effect = RuntimeError("Unavailable")
        receiver = Mock(label="receiver", latest={})
        receiver.report.return_value = {"status": "Stopped"}
        checks = []
        read_final_reports([source, receiver], checks)
        self.assertEqual(source.latest["status"], "Last observation")
        self.assertEqual(receiver.latest["status"], "Stopped")
        self.assertEqual(checks, [{"name": "source final report", "result": "failed"}])

    def test_failed_exercise_reads_reports_before_cleanup(self):
        destination = MagicMock()
        destination.is_relative_to.return_value = True
        (destination / "results.json").exists.return_value = False
        args = parse_args(SamplesArgumentTest.args)
        args.output = Mock()
        args.output.resolve.return_value = destination
        devices = [Mock(label=label, latest={}) for label in ("source", "receiver1", "receiver2")]
        events = []
        with patch("sync_run.parse_args", return_value=args), \
                patch("sync_run.Device", side_effect=devices), \
                patch("sync_run.QuietPlayback"), patch("sync_run.Forward"), patch("sync_run.Reverse"), \
                patch("sync_run.exercise", side_effect=AssertionError("Sampling failed")), \
                patch("sync_run.read_final_reports", side_effect=lambda *args: events.append("reports")), \
                patch("sync_run.stop_devices", side_effect=lambda *args: events.append("stop")), \
                patch("builtins.print"):
            self.assertEqual(main(), 1)
        self.assertEqual(events, ["reports", "stop"])


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
            "relay": {"clockReady": True, "timedFrames": 50, "clockUncertaintyNanos": 4_000_000,
                      "peers": 1, "droppedFrames": 0, "publishedFrames": 0},
            "output": {"timingState": "playing", "scheduledStarts": 1, "queuedFrames": 60,
                       "startErrorNanos": 1_000_000, "outputErrorNanos": 4_000_000,
                       "playbackHeadFrames": 16000, "writtenBytes": 64000,
                       "resyncs": 1, "droppedFrames": 73, "lateFrames": 0, "insertedSilenceFrames": 0},
            "receivedPcm": {"nonSilentFrames": 100},
            "capture": {"running": True, "frames": 100, "nonSilentFrames": 50},
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

    def progressed(self):
        report = deepcopy(self.report)
        report["reportSequence"] += 1
        report["relay"]["timedFrames"] += 50
        report["output"]["writtenBytes"] += 32000
        report["output"]["playbackHeadFrames"] += 16000
        report["receivedPcm"]["nonSilentFrames"] += 50
        return report

    def test_sustained_output_allows_existing_startup_drops_but_requires_audio_progress(self):
        report = self.progressed()
        self.assertTrue(sustained_timing(report, self.report, self.report, "receiver"))
        report["receivedPcm"]["nonSilentFrames"] = self.report["receivedPcm"]["nonSilentFrames"]
        self.assertFalse(sustained_timing(report, self.report, self.report, "receiver"))
        self.assertFalse(sustained_timing(self.report, self.report, self.report, "receiver"))

    def test_sustained_output_requires_advancing_playback_head(self):
        report = self.progressed()
        report["output"]["playbackHeadFrames"] = self.report["output"]["playbackHeadFrames"]
        self.assertFalse(sustained_timing(report, self.report, self.report, "receiver"))

    def test_every_new_recovery_or_loss_counter_fails_even_after_output_recovers(self):
        groups = {"relay": ("peers", "droppedFrames"),
                  "output": ("scheduledStarts", "resyncs", "droppedFrames", "lateFrames", "insertedSilenceFrames")}
        for group, fields in groups.items():
            for field in fields:
                with self.subTest(group=group, field=field):
                    report = self.progressed()
                    report[group][field] += 1
                    with self.assertRaises(AssertionError):
                        sustained_timing(report, self.report, self.report, "receiver")

    def test_lost_clock_does_not_wait_for_a_recovery_to_pass(self):
        report = self.progressed()
        report["relay"]["clockReady"] = False
        with self.assertRaises(AssertionError):
            sustained_timing(report, self.report, self.report, "receiver")

    def test_restarted_report_sequence_fails(self):
        report = self.progressed()
        report["reportSequence"] = 1
        with self.assertRaises(AssertionError):
            sustained_timing(report, self.report, self.report, "receiver")

    def test_source_needs_new_captured_audio_and_published_frames(self):
        report = deepcopy(self.report)
        report["reportSequence"] += 1
        report["capture"]["frames"] += 50
        report["relay"]["publishedFrames"] += 50
        self.assertFalse(advancing_source(report, self.report, self.report))
        report["capture"]["nonSilentFrames"] += 50
        self.assertTrue(advancing_source(report, self.report, self.report))
        self.assertFalse(advancing_source(report, report, self.report))

    def test_source_stop_or_peer_loss_fails_immediately(self):
        for group, field, value in (("capture", "running", False), ("relay", "peers", 0)):
            with self.subTest(field=field):
                report = deepcopy(self.report)
                report[group][field] = value
                with self.assertRaises(AssertionError):
                    advancing_source(report, self.report, self.report)


if __name__ == "__main__":
    unittest.main()
