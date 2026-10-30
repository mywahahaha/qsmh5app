from dataclasses import dataclass, replace
from pathlib import Path
from typing import Optional


@dataclass(frozen=True)
class GameConfig:
    game_id: int
    game_version: str
    application_name: str
    application_id: str
    keystore_path: Path
    key_alias: str
    signing_password: Optional[str]
    icon_path: Path
    splash_dir: Path
    output_dir: Path
    orientation: str = "landscape"

    def with_updates(self, **values):
        return replace(self, **values)
