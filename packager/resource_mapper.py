import re
import shutil
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Tuple

from .models import GameConfig
from .validators import _is_png


SPLASH_DIMENSION_PATTERN = re.compile(r"(?<!\d)(\d{3,5})x(\d{3,5})(?!\d)", re.IGNORECASE)


@dataclass(frozen=True)
class MappedResources:
    icon_path: Path
    splash_names: Tuple[str, ...]


def prepare(config: GameConfig, staging_dir: Path) -> MappedResources:
    if not _is_png(config.icon_path):
        raise ValueError("圖标必须是有效 PNG 文件")
    if not config.splash_dir.is_dir():
        raise ValueError("闪图目录不存在")

    splash_files = sorted(path for path in config.splash_dir.iterdir() if path.is_file())
    mapped_splashes = {}
    for path in splash_files:
        if not _is_png(path):
            raise ValueError("闪图目录包含无效 PNG 文件")
        match = SPLASH_DIMENSION_PATTERN.search(path.stem)
        if match is None:
            raise ValueError("闪图文件名必须包含宽高尺寸，例如 1280x768")
        width, height = (int(value) for value in match.groups())
        if width <= 0 or height <= 0 or width == height:
            raise ValueError("闪图尺寸必须为有效的横屏或竖屏尺寸")
        orientation = "landscape" if width > height else "portrait"
        name = "{}_{}x{}".format(orientation, width, height)
        mapped_splashes.setdefault(name, path)

    resource_dir = staging_dir / "app" / "src" / "main" / "res"
    icon_target = resource_dir / "mipmap-nodpi" / "ic_launcher.png"
    icon_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(config.icon_path, icon_target)
    competing_launcher = resource_dir / "mipmap-anydpi" / "ic_launcher.xml"
    if competing_launcher.exists():
        competing_launcher.unlink()

    splash_target_dir = resource_dir / "drawable-nodpi"
    if mapped_splashes:
        splash_target_dir.mkdir(parents=True, exist_ok=True)
    for name, source in mapped_splashes.items():
        shutil.copyfile(source, splash_target_dir / "splash_{}.png".format(name))

    values_dir = resource_dir / "values"
    values_dir.mkdir(parents=True, exist_ok=True)
    resources = ET.Element("resources")
    images = ET.SubElement(resources, "array", name="qsm_splash_images")
    for name in mapped_splashes:
        ET.SubElement(images, "item").text = "@drawable/splash_{}".format(name)
    ET.ElementTree(resources).write(values_dir / "splashes.xml", encoding="utf-8", xml_declaration=True)

    return MappedResources(icon_target, tuple(mapped_splashes))
