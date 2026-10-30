import unittest
from pathlib import Path


class GradlePropertyEncodingTest(unittest.TestCase):
    def test_gradle_reads_gui_properties_as_utf8(self):
        build_file = Path(__file__).resolve().parents[2] / "android" / "app" / "build.gradle.kts"
        content = build_file.read_text(encoding="utf-8")

        self.assertEqual(2, content.count("file(propertiesPath).reader(Charsets.UTF_8).use { load(it) }"))

    def test_game_orientation_is_injected_into_activity_manifest(self):
        project_root = Path(__file__).resolve().parents[2]
        build_file = (project_root / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        manifest = (project_root / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")

        self.assertIn('manifestPlaceholders["gameOrientation"] = configuredOrientation', build_file)
        self.assertIn('android:screenOrientation="${gameOrientation}"', manifest)


if __name__ == "__main__":
    unittest.main()
