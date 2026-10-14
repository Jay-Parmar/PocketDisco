import io
import json
import unittest
from pathlib import Path
from unittest.mock import MagicMock, Mock, call, patch
from xml.etree.ElementTree import Element

from nearby import (
    ApiReverse,
    ProductDevice,
    api_mapping,
    local_api_address,
    main,
    parse_args,
    product_device,
    product_prompt,
    verify_backend,
    verify_product,
)


class BackendModeTest(unittest.TestCase):
    arguments = (
        "--adb",
        "unused",
        "--phone",
        "test-phone",
        "--output",
        ".local-tools/nearby-unused",
    )

    def test_default_remains_local_and_other_host_port_requires_opt_in(self):
        args = parse_args(self.arguments)
        self.assertFalse(args.deployed_backend)
        self.assertEqual(args.host_port, 8000)
        with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
            parse_args([*self.arguments, "--host-port", "18080"])
        args = parse_args(
            [*self.arguments, "--deployed-backend", "--host-port", "18080"]
        )
        self.assertTrue(args.deployed_backend)
        self.assertEqual(args.host_port, 18080)

    def test_production_mode_uses_bounded_private_backend_health(self):
        args = parse_args(
            [*self.arguments, "--deployed-backend", "--host-port", "18080"]
        )
        with (
            patch("tools.mobile_e2e.rooms.health") as health,
            patch("nearby.urlopen") as local,
        ):
            self.assertEqual(verify_backend(args), "production")
        health.assert_called_once_with(18080)
        local.assert_not_called()

    def test_production_health_failure_is_not_relabeled_success(self):
        args = parse_args(
            [*self.arguments, "--deployed-backend", "--host-port", "18080"]
        )
        with (
            patch(
                "tools.mobile_e2e.rooms.health",
                side_effect=RuntimeError("backend unavailable"),
            ),
            self.assertRaises(RuntimeError),
        ):
            verify_backend(args)

    def test_default_health_still_requires_local_test(self):
        args = parse_args(self.arguments)
        response = MagicMock()
        response.__enter__.return_value = response
        with (
            patch("nearby.urlopen", return_value=response) as request,
            patch("nearby.json.load", return_value={"mode": "production"}),
            self.assertRaises(AssertionError),
        ):
            verify_backend(args)
        request.assert_called_once_with("http://127.0.0.1:8000/healthz", timeout=5)

    def test_deployed_mapping_preserves_matching_tunnel_and_rejects_conflicts(self):
        args = parse_args(
            [*self.arguments, "--deployed-backend", "--host-port", "18080"]
        )
        for target in ("tcp:18080", "tcp:9999"):
            device = Mock()
            device.command.return_value = f"UsbFfs tcp:8000 {target}\n"
            with self.subTest(target=target):
                if target == "tcp:18080":
                    with api_mapping(device, args):
                        pass
                else:
                    with self.assertRaises(RuntimeError), api_mapping(device, args):
                        pass
            self.assertTrue(
                all(
                    item == call("reverse", "--list")
                    for item in device.command.call_args_list
                )
            )

    def test_deployed_product_uses_welcome_and_saved_origin_guards(self):
        args = parse_args(
            [*self.arguments, "--deployed-backend", "--host-port", "18080"]
        )
        with patch("tools.mobile_e2e.rooms.RoomDevice") as guarded:
            device = product_device(args)
        guarded.assert_called_once_with(
            "unused", "test-phone", "product phone", names=("Nearby Host",)
        )
        self.assertEqual(verify_product(device, args), "PocketDisco Room Test")
        device.verify_welcome.assert_called_once_with("Nearby Host")
        device.verify_local_api.assert_not_called()

    def test_saved_room_guard_does_not_fall_back_to_unguarded_setup(self):
        args = parse_args([*self.arguments, "--deployed-backend"])
        device = Mock()
        device.verify_welcome.side_effect = RuntimeError("saved room")
        with self.assertRaises(RuntimeError):
            verify_product(device, args)
        device.verify_local_api.assert_not_called()
        device.fill_id.assert_not_called()

    def test_backend_report_is_fixed_and_preflight_failure_does_not_stop_apps(self):
        args = parse_args(
            [*self.arguments, "--deployed-backend", "--host-port", "18080"]
        )
        for verified in (False, True):
            phone, receiver = Mock(), Mock(latest={})
            with (
                self.subTest(verified=verified),
                patch("nearby.parse_args", return_value=args),
                patch.object(Path, "exists", return_value=False),
                patch.object(Path, "mkdir"),
                patch.object(Path, "write_text") as write,
                patch("nearby.product_device", return_value=phone),
                patch("nearby.Device", return_value=receiver),
                patch("nearby.verify_backend", return_value="production"),
                patch("nearby.api_mapping"),
                patch(
                    "nearby.verify_product",
                    side_effect=None
                    if verified
                    else RuntimeError("private invite token"),
                    return_value="PocketDisco Room Test",
                ),
                patch("nearby.QuietPlayback") as quiet,
                patch(
                    "nearby.Forward", side_effect=RuntimeError("private invite token")
                ),
                patch("builtins.print") as printer,
            ):
                self.assertEqual(main(), 1)
            report = json.loads(write.call_args.args[0])
            self.assertEqual(
                report["backend"], "production" if verified else "not verified"
            )
            self.assertEqual(report["hostPort"], 18080)
            self.assertFalse(report["publicNetworkTested"])
            self.assertEqual(report["checks"][-1]["reason"], "nearby_check_failed")
            self.assertNotIn("private invite token", write.call_args.args[0])
            self.assertNotIn("private invite token", str(printer.call_args_list))
            phone.command.assert_not_called()
            receiver.command.assert_not_called()
            if not verified:
                quiet.assert_not_called()


class ApiReverseTest(unittest.TestCase):
    def test_does_not_replace_a_different_host_port(self):
        device = Mock()
        device.command.return_value = "UsbFfs tcp:8000 tcp:18080\n"
        with self.assertRaises(RuntimeError), ApiReverse(device):
            pass
        device.command.assert_called_once_with("reverse", "--list")

    def test_preserves_an_existing_compatible_mapping(self):
        device = Mock()
        device.command.return_value = "UsbFfs tcp:8000 tcp:8000\n"
        with ApiReverse(device):
            pass
        device.command.assert_called_once_with("reverse", "--list")

    def test_removes_only_its_own_mapping(self):
        device = Mock()
        device.command.side_effect = ["", "", "UsbFfs tcp:8000 tcp:8000\n", ""]
        with ApiReverse(device):
            pass
        self.assertEqual(
            device.command.call_args, call("reverse", "--remove", "tcp:8000")
        )

    def test_leaves_changed_mapping_alone(self):
        device = Mock()
        device.command.side_effect = ["", "", "UsbFfs tcp:8000 tcp:18080\n"]
        with ApiReverse(device):
            pass
        self.assertEqual(device.command.call_args, call("reverse", "--list"))


class PromptTest(unittest.TestCase):
    def test_prompt_must_name_the_product(self):
        self.assertTrue(
            product_prompt([Element("node", text="Allow PocketDisco to record audio?")])
        )
        self.assertFalse(
            product_prompt([Element("node", text="Allow another app to record audio?")])
        )
        self.assertFalse(product_prompt([Element("node", text="NotPocketDisco")]))
        self.assertFalse(
            product_prompt(
                [Element("node", text="Allow PocketDisco Capture Lab to record audio?")]
            )
        )

    def test_server_must_use_the_loopback_reverse(self):
        self.assertTrue(
            local_api_address(Element("node", text="http://127.0.0.1:8000/"))
        )
        self.assertFalse(
            local_api_address(Element("node", text="https://api.example.test"))
        )
        self.assertFalse(
            local_api_address(Element("node", text="http://127.0.0.1:8001"))
        )


class FormTest(unittest.TestCase):
    def device_with_input(self, text, hint=""):
        device = ProductDevice("adb", "test-phone", "product phone")
        device.find_id = Mock(return_value=Element("node", text=text, hint=hint))
        device.tap_node = Mock()
        device.command = Mock()
        return device

    def test_hint_is_not_existing_input(self):
        device = self.device_with_input("Your name", "Your name")
        device.fill_id("display-name-input", "Nearby Host")
        device.command.assert_any_call("shell", "input", "text", "Nearby%sHost")

    def test_existing_input_is_left_intact(self):
        device = self.device_with_input("Saved name", "Your name")
        with self.assertRaises(AssertionError):
            device.fill_id("display-name-input", "Nearby Host")
        device.tap_node.assert_not_called()
        device.command.assert_not_called()

    def test_matching_test_name_needs_no_edit(self):
        device = self.device_with_input("Nearby Host", "Your name")
        device.fill_id("display-name-input", "Nearby Host")
        device.tap_node.assert_not_called()
        device.command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
