import tempfile
import unittest
from pathlib import Path

from packager.config_store import GameConfigStore
from packager.models import GameConfig


class GameConfigStoreTest(unittest.TestCase):
    def test_persisted_config_never_contains_signing_password(self):
        with tempfile.TemporaryDirectory() as directory:
            config = GameConfig(266, "1.0.1", "Game", "com.example.game", Path("release.jks"), "release", "secret-value", Path("icon.png"), Path("splash"), Path("output"))
            store = GameConfigStore(Path(directory) / "games.json")
            store.save("game", config)
            saved_text = (Path(directory) / "games.json").read_text(encoding="utf-8")
            loaded = store.load("game")

            self.assertNotIn("secret-value", saved_text)
            self.assertNotIn("signing_password", saved_text)
            self.assertIsNone(loaded.signing_password)
            self.assertEqual("com.example.game", loaded.application_id)

    def test_save_and_load_individual_config_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "配置.json"
            config = GameConfig(266, "1.0.1", "Game", "com.example.game", Path("release.jks"), "release", "secret", Path("icon.png"), Path("splash"), Path("output"), "portrait")
            store = GameConfigStore(Path(directory) / "unused.json")

            store.save_file(path, config)
            loaded = store.load_file(path)

            self.assertEqual(config.game_id, loaded.game_id)
            self.assertEqual(config.orientation, loaded.orientation)
            self.assertIsNone(loaded.signing_password)

    def test_load_invalid_individual_config_file_raises_value_error(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "invalid.json"
            path.write_text("{broken", encoding="utf-8")
            store = GameConfigStore(Path(directory) / "unused.json")

            with self.assertRaises(ValueError):
                store.load_file(path)


if __name__ == "__main__":
    unittest.main()
