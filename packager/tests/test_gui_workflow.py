import queue
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

from packager.gui import BuildJob, PackagerWindow, config_from_form
from packager.models import GameConfig


class GuiWorkflowTest(unittest.TestCase):
    def test_success_result_shows_close_button_and_button_closes_window(self):
        window = PackagerWindow.__new__(PackagerWindow)
        window.root = Mock()
        window.build_button = Mock()
        window.success_close_button = Mock()
        window.status = Mock()
        result = SimpleNamespace(
            success=True,
            apk_path=Path("/output/game.apk"),
        )

        window._handle_build_result(result)

        window.success_close_button.grid.assert_called_once_with()
        self.assertEqual(0, window.success_close_button.grid_remove.call_count)
        window._close_window()
        window.root.destroy.assert_called_once_with()

    def test_failed_result_hides_close_button(self):
        window = PackagerWindow.__new__(PackagerWindow)
        window.build_button = Mock()
        window.success_close_button = Mock()
        window.status = Mock()

        window._handle_build_result(None)

        window.success_close_button.grid_remove.assert_called_once_with()
        self.assertEqual(0, window.success_close_button.grid.call_count)

    def test_game_id_has_no_default_and_game_orientation_defaults_to_landscape(self):
        defaults = {key: default for key, _label, default in PackagerWindow.FIELDS}
        config = GameConfig(266, "1.0.1", "Game", "com.example.game", Path("sign.jks"), "release", None, Path("icon.png"), Path("splash"), Path("output"))

        self.assertEqual("", defaults["game_id"])
        self.assertEqual("landscape", config.orientation)

    def test_form_values_keep_selected_paths_and_secret_only_in_memory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            values = {
                "game_id": "266",
                "game_version": "1.0.1",
                "application_name": "天尊传奇",
                "application_id": "com.example.game",
                "keystore_path": str(root / "sign.jks"),
                "key_alias": "release",
                "signing_password": "secret",
                "icon_path": str(root / "icon.png"),
                "splash_dir": str(root / "splash"),
                "output_dir": str(root / "output"),
                "orientation": "portrait",
            }

            config = config_from_form(values)

            self.assertEqual(Path(values["icon_path"]), config.icon_path)
            self.assertEqual("secret", config.signing_password)
            self.assertEqual("portrait", config.orientation)

    def test_build_job_runs_builder_off_ui_thread_and_publishes_result(self):
        result_queue = queue.Queue()
        ui_thread = __import__("threading").current_thread()
        worker_threads = []
        config = GameConfig(266, "1.0.1", "Game", "com.example.game", Path("sign.jks"), "release", "secret", Path("icon.png"), Path("splash"), Path("output"))

        class SlowBuilder:
            def build(self, received, progress_callback):
                worker_threads.append(__import__("threading").current_thread())
                progress_callback("正在构建")
                time.sleep(0.05)
                return "finished"

        job = BuildJob(SlowBuilder(), result_queue)

        job.start(config)

        self.assertIsNot(ui_thread, worker_threads[0])
        messages = []
        deadline = time.time() + 2
        while time.time() < deadline:
            try:
                messages.append(result_queue.get(timeout=0.1))
            except queue.Empty:
                continue
            if messages[-1][0] == "result":
                break
        self.assertIn(("progress", "正在构建"), messages)
        self.assertIn(("result", "finished"), messages)


if __name__ == "__main__":
    unittest.main()
