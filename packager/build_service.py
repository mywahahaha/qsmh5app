import os
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable, List, Optional

from .models import GameConfig
from .resource_mapper import prepare
from .validators import validate_game_config


@dataclass(frozen=True)
class BuildResult:
    success: bool
    apk_path: Optional[Path]
    message: str


class BuildService:
    def __init__(self, android_dir: Path, runner: Callable = subprocess.run):
        self.android_dir = android_dir.resolve()
        self.runner = runner

    def build(self, config: GameConfig, progress_callback: Optional[Callable[[str], None]] = None) -> BuildResult:
        errors = validate_game_config(config)
        if errors:
            return BuildResult(False, None, "；".join(errors))
        if not (self.android_dir / "app" / "build.gradle.kts").is_file():
            return BuildResult(False, None, "Android 模板目录不完整")
        android_sdk = _resolve_android_sdk(os.environ)
        if android_sdk is None:
            return BuildResult(False, None, "找不到 Android SDK，请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT")

        report = progress_callback or (lambda _message: None)
        try:
            with tempfile.TemporaryDirectory(prefix="qsm-build-") as temporary:
                staging = Path(temporary) / "project"
                shutil.copytree(self.android_dir, staging, ignore=shutil.ignore_patterns(".gradle", "build", "local.properties"))
                (staging / "local.properties").write_text(
                    "sdk.dir={}\n".format(android_sdk.as_posix()),
                    encoding="utf-8",
                )
                prepare(config, staging)
                signing_properties = Path(temporary) / "signing.properties"
                signing_properties.write_text(
                    "storeFile={}\nkeyAlias={}\nstorePassword={}\nkeyPassword={}\n".format(
                        _escape_property(str(config.keystore_path.resolve())),
                        _escape_property(config.key_alias),
                        _escape_property(config.signing_password or ""),
                        _escape_property(config.signing_password or ""),
                    ),
                    encoding="utf-8",
                )
                try:
                    signing_properties.chmod(0o600)
                except OSError:
                    pass
                build_properties = Path(temporary) / "build.properties"
                build_properties.write_text(
                    "gameId={}\ngameVersion={}\napplicationId={}\nappName={}\norientation={}\n".format(
                        config.game_id,
                        _escape_property(config.game_version),
                        _escape_property(config.application_id),
                        _escape_property(config.application_name),
                        config.orientation,
                    ),
                    encoding="utf-8",
                )

                wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
                command: List[str] = [
                    str(staging / wrapper),
                    "--no-daemon",
                    "--console=plain",
                    "assembleRelease",
                ]
                environment = os.environ.copy()
                environment["QSM_SIGNING_PROPERTIES"] = str(signing_properties)
                environment["QSM_BUILD_PROPERTIES"] = str(build_properties)
                report("正在构建签名 APK…")
                result = self.runner(
                    command,
                    cwd=staging,
                    env=environment,
                    capture_output=True,
                    text=True,
                    check=False,
                )
                if result.returncode != 0:
                    log_path = config.output_dir / "build_{}_{}.log".format(
                        config.game_id, _safe_filename(config.game_version)
                    )
                    _write_build_log(log_path, result, config.signing_password)
                    return BuildResult(False, None, "Gradle 构建失败，详细信息见构建日志：{}".format(log_path.resolve()))

                built_apk = staging / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
                if not built_apk.is_file():
                    return BuildResult(False, None, "Gradle 未生成预期的 APK 文件")
                report("正在验证 APK 签名并读取证书 MD5…")
                try:
                    package_signature = _apk_certificate_md5(built_apk, android_sdk, self.runner)
                except (OSError, ValueError) as error:
                    return BuildResult(False, None, "APK 签名读取失败：{}".format(error))
                output_stem = "{}_{}".format(config.application_id, _safe_filename(config.game_version))
                output_apk = config.output_dir / (output_stem + ".apk")
                output_info = config.output_dir / (output_stem + ".txt")
                temporary_apk = output_apk.with_suffix(output_apk.suffix + ".tmp")
                temporary_info = output_info.with_suffix(output_info.suffix + ".tmp")
                shutil.copyfile(built_apk, temporary_apk)
                temporary_info.write_text(
                    "游戏名称：{}\n游戏ID：{}\n游戏版本: {}\n包名：{}\n包签名：{}\n生成时间: {}\n".format(
                        config.application_name,
                        config.game_id,
                        config.game_version,
                        config.application_id,
                        package_signature,
                        datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S"),
                    ),
                    encoding="utf-8",
                )
                os.replace(temporary_apk, output_apk)
                os.replace(temporary_info, output_info)
                report("APK 构建完成")
                return BuildResult(True, output_apk.resolve(), "构建成功")
        except (OSError, ValueError, shutil.Error):
            return BuildResult(False, None, "构建准备失败，请检查所选文件和目录权限")


def _escape_property(value: str) -> str:
    return value.replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=").replace("\n", "\\n").replace("\r", "\\r")


def _safe_filename(value: str) -> str:
    return "".join(character if character.isalnum() or character in ".-" else "_" for character in value)


def _apk_certificate_md5(apk_path: Path, android_sdk: Path, runner: Callable) -> str:
    tool_name = "apksigner.bat" if os.name == "nt" else "apksigner"
    tools = [path for path in (android_sdk / "build-tools").glob("*/" + tool_name) if path.is_file()]
    if not tools:
        raise ValueError("找不到 apksigner，请安装 Android SDK Build Tools")
    signer = max(tools, key=lambda path: (
        tuple(int(part) for part in re.findall(r"\d+", path.parent.name.split("-")[0])),
        "-" not in path.parent.name,
    ))
    result = runner(
        [str(signer), "verify", "--print-certs", str(apk_path)],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise ValueError("APK 签名验证失败")
    fingerprints = re.findall(r"^Signer #\d+ certificate MD5 digest:[ \t]*([^\r\n]*)", result.stdout or "", re.MULTILINE)
    if len(fingerprints) != 1:
        raise ValueError("未读取到唯一的 APK 签名证书 MD5 指纹")
    fingerprint = fingerprints[0].strip().replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{32}", fingerprint):
        raise ValueError("APK 签名证书 MD5 指纹格式无效")
    return fingerprint


def _write_build_log(path: Path, result, signing_password: Optional[str]) -> None:
    stdout = result.stdout or ""
    stderr = result.stderr or ""
    if signing_password:
        stdout = stdout.replace(signing_password, "[已脱敏]")
        stderr = stderr.replace(signing_password, "[已脱敏]")
    path.write_text(
        "退出码：{}\n\n--- 标准输出 ---\n{}\n\n--- 错误输出 ---\n{}".format(
            result.returncode, stdout, stderr
        ),
        encoding="utf-8",
    )


def _resolve_android_sdk(environment) -> Optional[Path]:
    candidates = [environment.get("ANDROID_HOME"), environment.get("ANDROID_SDK_ROOT")]
    local_app_data = environment.get("LOCALAPPDATA")
    if local_app_data:
        candidates.append(str(Path(local_app_data) / "Android" / "Sdk"))
    for candidate in candidates:
        if candidate:
            path = Path(candidate).expanduser()
            if path.is_dir():
                return path.resolve()
    return None
