import tempfile
import unittest
from pathlib import Path

from packager.models import GameConfig
from packager.validators import validate_game_config


class GameConfigValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.icon = root / "icon.png"
        self.icon.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + b"\x00\x00\x00\x10\x00\x00\x00\x10")
        self.keystore = root / "release.jks"
        self.keystore.write_bytes(b"keystore")
        self.splash = root / "splash"
        self.splash.mkdir()
        self.output = root / "output"
        self.output.mkdir()
        self.config = GameConfig(266, "1.0.1", "Game", "com.example.game", self.keystore, "release", "secret", self.icon, self.splash, self.output)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_accepts_complete_game_configuration(self):
        self.assertEqual([], validate_game_config(self.config))

    def test_rejects_bad_game_id_application_id_icon_and_missing_paths(self):
        invalid = self.config.with_updates(game_id=-1, application_id="bad package", icon_path=self.output / "missing.jpg", splash_dir=self.output / "missing", keystore_path=self.output / "missing.jks")
        errors = validate_game_config(invalid)
        self.assertIn("gameId 必须是正整数", errors)
        self.assertIn("Android 包名格式无效", errors)
        self.assertIn("图标必须是有效 PNG 文件", errors)
        self.assertIn("闪图目录不存在", errors)
        self.assertIn("签名证书文件不存在", errors)

    def test_rejects_empty_build_fields_and_malformed_png(self):
        malformed_icon = self.output / "malformed.png"
        malformed_icon.write_bytes(b"\\x89PNG\\r\\n\\x1a\\n" + b"\\x00\\x00\\x00\\x0dIHDR" + b"\\x00\\x00\\x00\\x10\\x00\\x00\\x00\\x10")
        invalid = self.config.with_updates(game_version=" ", application_name="", key_alias="", icon_path=malformed_icon)
        errors = validate_game_config(invalid)
        self.assertIn("gameVersion 不能为空", errors)
        self.assertIn("APP 桌面名称不能为空", errors)
        self.assertIn("签名 alias 不能为空", errors)
        self.assertIn("图标必须是有效 PNG 文件", errors)


if __name__ == "__main__":
    unittest.main()
