import unittest
from xml.etree.ElementTree import Element

from run import visible


class VisibilityTest(unittest.TestCase):
    def test_visible_node(self):
        self.assertTrue(visible(Element("node", bounds="[10,20][100,60]")))

    def test_clipped_or_missing_bounds(self):
        for bounds in ("", "[0,0]", "[10,60][100,40]", "[10,20][10,60]"):
            with self.subTest(bounds=bounds):
                self.assertFalse(visible(Element("node", bounds=bounds)))


if __name__ == "__main__":
    unittest.main()
