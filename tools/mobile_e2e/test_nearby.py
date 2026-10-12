import unittest
from unittest.mock import Mock, call
from xml.etree.ElementTree import Element

from nearby import ApiReverse, ProductDevice, local_api_address, product_prompt


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
