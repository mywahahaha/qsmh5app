import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from packager.models import GameConfig
from packager.resource_mapper import prepare


class ResourceMapperTest(unittest.TestCase):
    def test_copies_png_icon_and_classifies_splash_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            icon = root / "icon.png"
            icon.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + b"\x00\x00\x00\x10\x00\x00\x00\x10")
            splash = root / "splash"
            splash.mkdir()
            (splash / "横板-1280x768.png").write_bytes(icon.read_bytes())
            launcher_xml = root / "staging/app/src/main/res/mipmap-anydpi/ic_launcher.xml"
            launcher_xml.parent.mkdir(parents=True)
            launcher_xml.write_text("<vector />", encoding="utf-8")
            config = GameConfig(266, "1.0", "Game", "com.example.game", root / "sign.jks", "release", None, icon, splash, root / "out")

            mapped = prepare(config, root / "staging")

            self.assertEqual(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + b"\x00\x00\x00\x10\x00\x00\x00\x10", mapped.icon_path.read_bytes())
            self.assertEqual(["landscape_1280x768"], list(mapped.splash_names))
            self.assertTrue((root / "staging/app/src/main/res/drawable-nodpi/splash_landscape_1280x768.png").is_file())
            self.assertFalse(launcher_xml.exists())
            index = ET.parse(root / "staging/app/src/main/res/values/splashes.xml")
            self.assertEqual(
                ["@drawable/splash_landscape_1280x768"],
                [item.text for item in index.findall("./array[@name='qsm_splash_images']/item")],
            )

    def test_duplicate_dimensions_only_register_one_drawable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            icon = root / "icon.png"
            icon.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + b"\x00\x00\x00\x10\x00\x00\x00\x10")
            splash = root / "splash"
            splash.mkdir()
            for name in ("竖板-1242x2208.png", "竖版-1242x2208.png"):
                (splash / name).write_bytes(icon.read_bytes())
            config = GameConfig(266, "1.0", "Game", "com.example.game", root / "sign.jks", "release", None, icon, splash, root / "out")

            mapped = prepare(config, root / "staging")

            self.assertEqual(("portrait_1242x2208",), mapped.splash_names)

    def test_empty_directory_writes_an_empty_splash_index(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            icon = root / "icon.png"
            icon.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + b"\x00\x00\x00\x10\x00\x00\x00\x10")
            splash = root / "splash"
            splash.mkdir()
            config = GameConfig(266, "1.0", "Game", "com.example.game", root / "sign.jks", "release", None, icon, splash, root / "out")

            prepare(config, root / "staging")

            index = ET.parse(root / "staging/app/src/main/res/values/splashes.xml")
            self.assertIsNotNone(index.find("./array[@name='qsm_splash_images']"))
            self.assertEqual([], index.findall("./array/item"))

    def test_rejects_splash_filename_without_dimensions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            icon = root / "icon.png"
            icon.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + b"\x00\x00\x00\x10\x00\x00\x00\x10")
            splash = root / "splash"
            splash.mkdir()
            (splash / "opening.png").write_bytes(icon.read_bytes())
            config = GameConfig(266, "1.0", "Game", "com.example.game", root / "sign.jks", "release", None, icon, splash, root / "out")

            with self.assertRaisesRegex(ValueError, "尺寸"):
                prepare(config, root / "staging")


if __name__ == "__main__":
    unittest.main()
