import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from packager.build_service import BuildService
from packager.models import GameConfig


class BuildServiceTest(unittest.TestCase):
    def test_build_passes_signing_material_outside_arguments_and_copies_apk(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            android = root / "android"
            (android / "app").mkdir(parents=True)
            (android / "app/build.gradle.kts").write_text("", encoding="utf-8")
            (android / "gradlew.bat").write_text("", encoding="utf-8")
            icon = root / "icon.png"
            icon.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + b"\x00\x00\x00\x10\x00\x00\x00\x10")
            keystore = root / "release.jks"
            keystore.write_bytes(b"test")
            splash = root / "splash"
            splash.mkdir()
            (splash / "portrait-750x1334.png").write_bytes(icon.read_bytes())
            output = root / "output"
            output.mkdir()
            sdk = root / "Android" / "Sdk"
            signer = sdk / "build-tools" / "36.0.0" / ("apksigner.bat" if os.name == "nt" else "apksigner")
            signer.parent.mkdir(parents=True)
            signer.touch()
            config = GameConfig(92, "1.0.1", "天尊传奇", "com.meh5app.tianzun", keystore, "release", "secret-pass", icon, splash, output)
            observed = {}

            def runner(command, **kwargs):
                if Path(command[0]).name.startswith("apksigner"):
                    self.assertEqual(signer.resolve(), Path(command[0]))
                    self.assertEqual(["verify", "--print-certs"], command[1:3])
                    self.assertEqual(b"apk", Path(command[3]).read_bytes())
                    return type("Result", (), {"returncode": 0, "stdout": "Signer #1 certificate MD5 digest: 40:E9:C2:71:C0:88:11:0B:E9:75:C4:F2:78:53:D7:2C\n", "stderr": ""})()
                observed["command"] = command
                observed["env"] = kwargs["env"]
                observed["signing_file"] = Path(kwargs["env"]["QSM_SIGNING_PROPERTIES"])
                observed["build_file"] = Path(kwargs["env"]["QSM_BUILD_PROPERTIES"])
                observed["build_properties"] = observed["build_file"].read_text(encoding="utf-8")
                observed["local_properties"] = (kwargs["cwd"] / "local.properties").read_text(encoding="utf-8")
                built = kwargs["cwd"] / "app/build/outputs/apk/release/app-release.apk"
                built.parent.mkdir(parents=True)
                built.write_bytes(b"apk")
                return type("Result", (), {"returncode": 0, "stdout": "BUILD SUCCESSFUL", "stderr": ""})()

            with patch.dict(os.environ, {"LOCALAPPDATA": str(root)}, clear=True):
                result = BuildService(android, runner).build(config)

            self.assertTrue(result.success)
            self.assertEqual("com.meh5app.tianzun_1.0.1.apk", result.apk_path.name)
            self.assertEqual(b"apk", result.apk_path.read_bytes())
            info_path = result.apk_path.with_suffix(".txt")
            info = info_path.read_text(encoding="utf-8")
            self.assertEqual("com.meh5app.tianzun_1.0.1.txt", info_path.name)
            self.assertIn("游戏名称：天尊传奇\n", info)
            self.assertIn("游戏ID：92\n", info)
            self.assertIn("游戏版本: 1.0.1\n", info)
            self.assertIn("包名：com.meh5app.tianzun\n", info)
            self.assertIn("包签名：40e9c271c088110be975c4f27853d72c\n", info)
            self.assertRegex(info, r"生成时间: \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\n$")
            self.assertNotIn("secret-pass", " ".join(observed["command"]))
            self.assertNotIn("天尊传奇", " ".join(observed["command"]))
            self.assertTrue(observed["signing_file"].exists() is False)
            self.assertFalse(observed["build_file"].exists())
            self.assertIn("sdk.dir={}".format(sdk.resolve().as_posix()), observed["local_properties"])
            self.assertIn("orientation=landscape", observed["build_properties"])
            self.assertFalse(any("secret-pass" in path.read_text(encoding="utf-8", errors="ignore") for path in (root / "staging").rglob("*") if path.is_file()))

    def test_invalid_apk_signature_does_not_overwrite_existing_outputs(self):
        cases = [
            (None, ""),
            (1, "Signer #1 certificate MD5 digest: 40e9c271c088110be975c4f27853d72c\n"),
            (0, "Signer #1 certificate MD5 digest: invalid\n"),
            (0, ""),
            (0, "Signer #1 certificate MD5 digest: 40e9c271c088110be975c4f27853d72c\nSigner #2 certificate MD5 digest: 0123456789abcdef0123456789abcdef\n"),
        ]
        for returncode, stdout in cases:
            with self.subTest(returncode=returncode, stdout=stdout), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                android = root / "android"
                (android / "app").mkdir(parents=True)
                (android / "app/build.gradle.kts").touch()
                icon = root / "icon.png"
                icon.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + b"\x00\x00\x00\x10\x00\x00\x00\x10")
                keystore = root / "release.jks"
                keystore.write_bytes(b"test")
                splash = root / "splash"
                splash.mkdir()
                output = root / "output"
                output.mkdir()
                sdk = root / "Android" / "Sdk"
                signer = sdk / "build-tools" / "36.0.0" / ("apksigner.bat" if os.name == "nt" else "apksigner")
                signer.parent.mkdir(parents=True)
                if returncode is not None:
                    signer.touch()
                config = GameConfig(92, "1.0.1", "天尊传奇", "com.meh5app.tianzun", keystore, "release", "secret-pass", icon, splash, output)
                apk_path = output / "com.meh5app.tianzun_1.0.1.apk"
                info_path = apk_path.with_suffix(".txt")
                apk_path.write_bytes(b"old-apk")
                info_path.write_text("原有信息", encoding="utf-8")

                def runner(command, **kwargs):
                    if Path(command[0]).name.startswith("apksigner"):
                        return type("Result", (), {"returncode": returncode, "stdout": stdout, "stderr": "verification detail"})()
                    built = kwargs["cwd"] / "app/build/outputs/apk/release/app-release.apk"
                    built.parent.mkdir(parents=True)
                    built.write_bytes(b"new-apk")
                    return type("Result", (), {"returncode": 0, "stdout": "BUILD SUCCESSFUL", "stderr": ""})()

                with patch.dict(os.environ, {"LOCALAPPDATA": str(root)}, clear=True):
                    result = BuildService(android, runner).build(config)

                self.assertFalse(result.success)
                self.assertIsNone(result.apk_path)
                self.assertIn("签名", result.message)
                self.assertEqual(b"old-apk", apk_path.read_bytes())
                self.assertEqual("原有信息", info_path.read_text(encoding="utf-8"))
                self.assertFalse(list(output.glob("*.tmp")))

    def test_build_failure_does_not_return_signing_secret(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            android = root / "android"
            (android / "app").mkdir(parents=True)
            (android / "app/build.gradle.kts").write_text("", encoding="utf-8")
            (android / "gradlew.bat").write_text("", encoding="utf-8")
            icon = root / "icon.png"
            icon.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\x0dIHDR" + b"\x00\x00\x00\x10\x00\x00\x00\x10")
            keystore = root / "release.jks"
            keystore.write_bytes(b"test")
            splash = root / "splash"
            splash.mkdir()
            output = root / "output"
            output.mkdir()
            sdk = root / "Android" / "Sdk"
            sdk.mkdir(parents=True)
            config = GameConfig(266, "1.0.1", "Game", "com.example.game", keystore, "release", "secret-pass", icon, splash, output)
            runner = lambda *args, **kwargs: type("Result", (), {"returncode": 1, "stdout": "secret-pass Gradle failure detail", "stderr": "secret-pass"})()

            with patch.dict(os.environ, {"LOCALAPPDATA": str(root)}, clear=True):
                result = BuildService(android, runner).build(config)

            self.assertFalse(result.success)
            self.assertNotIn("secret-pass", result.message)
            self.assertIn("构建日志", result.message)
            log_files = list(output.glob("*.log"))
            self.assertEqual(1, len(log_files))
            self.assertNotIn("secret-pass", log_files[0].read_text(encoding="utf-8"))
            self.assertIn("Gradle failure detail", log_files[0].read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
