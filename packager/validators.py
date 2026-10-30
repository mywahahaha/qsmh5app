import re
import struct
from pathlib import Path
from typing import List

from .models import GameConfig


APPLICATION_ID_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def validate_game_config(config: GameConfig) -> List[str]:
    errors = []
    if config.orientation not in {"landscape", "portrait"}:
        errors.append("游戏全屏模式必须选择横屏或竖屏")
    if isinstance(config.game_id, bool) or not isinstance(config.game_id, int) or config.game_id <= 0:
        errors.append("gameId 必须是正整数")
    if not isinstance(config.game_version, str) or not config.game_version.strip():
        errors.append("gameVersion 不能为空")
    if not isinstance(config.application_name, str) or not config.application_name.strip():
        errors.append("APP 桌面名称不能为空")
    if not isinstance(config.application_id, str) or not APPLICATION_ID_PATTERN.fullmatch(config.application_id):
        errors.append("Android 包名格式无效")
    if not config.key_alias.strip():
        errors.append("签名 alias 不能为空")
    if not config.keystore_path.is_file():
        errors.append("签名证书文件不存在")
    elif config.keystore_path.suffix.lower() not in {".jks", ".keystore", ".p12", ".pfx"}:
        errors.append("签名证书格式不支持")
    if not _is_png(config.icon_path):
        errors.append("图标必须是有效 PNG 文件")
    if not config.splash_dir.is_dir():
        errors.append("闪图目录不存在")
    if not config.output_dir.is_dir():
        errors.append("输出目录不存在")
    return errors


def _is_png(path: Path) -> bool:
    try:
        with path.open("rb") as image:
            header = image.read(24)
        return len(header) == 24 and header[:8] == PNG_SIGNATURE and header[12:16] == b"IHDR" and all(struct.unpack(">II", header[16:24]))
    except OSError:
        return False
