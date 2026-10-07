import unittest
from argparse import ArgumentTypeError
from unittest.mock import patch
from xml.etree.ElementTree import Element

from run import Device, port_number, visible


class VisibilityTest(unittest.TestCase):
    def test_visible_node(self):
        self.assertTrue(visible(Element("node", bounds="[10,20][100,60]")))

    def test_clipped_or_missing_bounds(self):
        for bounds in ("", "[0,0]", "[10,60][100,40]", "[10,20][10,60]"):
            with self.subTest(bounds=bounds):
                self.assertFalse(visible(Element("node", bounds=bounds)))


class ApiConnectionTest(unittest.TestCase):
    def test_host_port_is_validated(self):
        self.assertEqual(port_number("18080"), 18080)
        for value in ("0", "65536", "not-a-port"):
            with self.subTest(value=value), self.assertRaises(ArgumentTypeError):
                port_number(value)

    def test_reverse_uses_selected_host_port(self):
        device = Device("adb", ["-d"], "phone")
        with patch.object(device, "command") as command:
            device.connect_to_api(18080)
        command.assert_called_once_with("reverse", "tcp:8000", "tcp:18080")


if __name__ == "__main__":
    unittest.main()
