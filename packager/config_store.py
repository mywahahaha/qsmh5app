import json
from dataclasses import fields
from pathlib import Path

from .models import GameConfig


class GameConfigStore:
    def __init__(self, path: Path):
        self.path = path

    def save(self, name: str, config: GameConfig) -> None:
        configs = self._read_all()
        configs[name] = self._serialize(config)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(json.dumps(configs, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(self.path)

    def save_file(self, path: Path, config: GameConfig) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self._serialize(config), ensure_ascii=False, indent=2), encoding="utf-8")

    def load(self, name: str) -> GameConfig:
        values = self._read_all()[name]
        return self._deserialize(values)

    def load_file(self, path: Path) -> GameConfig:
        try:
            with path.open(encoding="utf-8") as source:
                values = json.load(source)
            return self._deserialize(values)
        except (OSError, UnicodeError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
            raise ValueError("配置文件无效") from error

    @staticmethod
    def _serialize(config: GameConfig) -> dict:
        return {
            field.name: str(value) if isinstance(value, Path) else value
            for field in fields(config)
            if field.name != "signing_password"
            for value in (getattr(config, field.name),)
        }

    @staticmethod
    def _deserialize(values: dict) -> GameConfig:
        if not isinstance(values, dict):
            raise ValueError("配置文件无效")
        required = {field.name for field in fields(GameConfig)} - {"signing_password", "orientation"}
        if not required.issubset(values):
            raise ValueError("配置文件无效")
        allowed = required | {"orientation"}
        if set(values) - allowed:
            raise ValueError("配置文件无效")
        if not isinstance(values.get("orientation", "landscape"), str) or values.get("orientation", "landscape") not in ("landscape", "portrait"):
            raise ValueError("配置文件无效")
        if isinstance(values.get("game_id"), bool) or not isinstance(values.get("game_id"), int):
            raise ValueError("配置文件无效")
        for field in ("game_version", "application_name", "application_id", "key_alias"):
            if not isinstance(values[field], str):
                raise ValueError("配置文件无效")
        for field in ("keystore_path", "icon_path", "splash_dir", "output_dir"):
            if not isinstance(values[field], str):
                raise ValueError("配置文件无效")
        values = dict(values)
        for field in ("keystore_path", "icon_path", "splash_dir", "output_dir"):
            values[field] = Path(values[field])
        values["signing_password"] = None
        values.setdefault("orientation", "landscape")
        try:
            return GameConfig(**values)
        except TypeError as error:
            raise ValueError("配置文件无效") from error

    def _read_all(self) -> dict:
        if not self.path.exists():
            return {}
        with self.path.open(encoding="utf-8") as source:
            return json.load(source)
