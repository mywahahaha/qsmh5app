import queue
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from typing import Dict, Mapping

from .build_service import BuildService
from .config_store import GameConfigStore
from .models import GameConfig


def config_from_form(values: Mapping[str, str]) -> GameConfig:
    return GameConfig(
        game_id=int(values["game_id"]),
        game_version=values["game_version"],
        application_name=values["application_name"],
        application_id=values["application_id"],
        keystore_path=Path(values["keystore_path"]),
        key_alias=values["key_alias"],
        signing_password=values["signing_password"],
        icon_path=Path(values["icon_path"]),
        splash_dir=Path(values["splash_dir"]),
        output_dir=Path(values["output_dir"]),
        orientation=values["orientation"],
    )


class BuildJob:
    def __init__(self, builder: BuildService, messages: queue.Queue):
        self.builder = builder
        self.messages = messages

    def start(self, config: GameConfig) -> threading.Thread:
        def run() -> None:
            try:
                result = self.builder.build(config, lambda message: self.messages.put(("progress", message)))
            except Exception:
                result = None
            self.messages.put(("result", result))

        thread = threading.Thread(target=run, name="qsm-apk-build", daemon=True)
        thread.start()
        return thread


class PackagerWindow:
    FIELDS = (
        ("game_id", "游戏 ID", ""),
        ("game_version", "游戏版本", "1.0.0"),
        ("application_name", "APP 桌面名称", ""),
        ("application_id", "安卓包名", ""),
        ("keystore_path", "签名证书", ""),
        ("key_alias", "签名 Alias", ""),
        ("signing_password", "签名密码", ""),
        ("icon_path", "桌面图标 PNG", ""),
        ("splash_dir", "闪图目录", ""),
        ("output_dir", "APK 输出目录", str(Path.home() / "Desktop")),
    )

    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("启示梦 Android 游戏出包工具")
        self.root.geometry("760x610")
        self.variables: Dict[str, tk.StringVar] = {}
        self.messages = queue.Queue()
        project_root = Path(__file__).resolve().parents[1]
        self.builder = BuildJob(BuildService(project_root / "android"), self.messages)
        self.store = GameConfigStore(Path(__file__).resolve().parent / "configs" / "games.json")
        self._build_form()
        self.root.after(100, self._poll_messages)

    def _build_form(self) -> None:
        frame = ttk.Frame(self.root, padding=14)
        frame.pack(fill=tk.BOTH, expand=True)
        for row, (key, label, default) in enumerate(self.FIELDS):
            ttk.Label(frame, text=label).grid(row=row, column=0, sticky=tk.W, padx=6, pady=5)
            variable = tk.StringVar(value=default)
            self.variables[key] = variable
            entry = ttk.Entry(frame, textvariable=variable, show="*" if key == "signing_password" else "")
            entry.grid(row=row, column=1, sticky=tk.EW, padx=6, pady=5)
            if key in ("keystore_path", "icon_path"):
                command = self._choose_file(key)
                label_text = "选择证书" if key == "keystore_path" else "选择 PNG"
                ttk.Button(frame, text=label_text, command=command).grid(row=row, column=2, padx=6)
            elif key in ("splash_dir", "output_dir"):
                ttk.Button(frame, text="选择目录", command=self._choose_directory(key)).grid(row=row, column=2, padx=6)
        frame.columnconfigure(1, weight=1)

        config_row = len(self.FIELDS)
        ttk.Label(frame, text="配置名称").grid(row=config_row, column=0, sticky=tk.W, padx=6, pady=5)
        self.config_name = tk.StringVar()
        ttk.Entry(frame, textvariable=self.config_name).grid(row=config_row, column=1, sticky=tk.EW, padx=6)
        ttk.Button(frame, text="保存配置", command=self._save_config).grid(row=config_row, column=2, padx=6)
        ttk.Button(frame, text="加载配置", command=self._load_config).grid(row=config_row + 1, column=2, padx=6)

        orientation_row = config_row + 1
        ttk.Label(frame, text="游戏全屏模式").grid(row=orientation_row, column=0, sticky=tk.W, padx=6, pady=5)
        self.orientation = tk.StringVar(value="landscape")
        ttk.Radiobutton(frame, text="横屏", variable=self.orientation, value="landscape").grid(row=orientation_row, column=1, sticky=tk.W, padx=6)
        ttk.Radiobutton(frame, text="竖屏", variable=self.orientation, value="portrait").grid(row=orientation_row, column=1, sticky=tk.W, padx=90)

        self.status = tk.StringVar(value="填写参数后构建 APK；签名密码不会保存到配置文件。")
        ttk.Label(frame, textvariable=self.status, wraplength=700).grid(row=config_row + 2, column=0, columnspan=2, sticky=tk.W, padx=6, pady=14)
        self.build_button = ttk.Button(frame, text="构建签名 APK", command=self._start_build)
        self.build_button.grid(row=config_row + 3, column=0, columnspan=3, sticky=tk.EW, padx=6, pady=8)
        self.success_close_button = ttk.Button(
            frame,
            text="出包成功，关闭窗口",
            command=self._close_window,
        )
        self.success_close_button.grid(row=config_row + 4, column=0, columnspan=3, sticky=tk.EW, padx=6, pady=8)
        self.success_close_button.grid_remove()

    def _choose_file(self, key: str):
        def choose() -> None:
            filters = [("PNG 图片", "*.png")] if key == "icon_path" else [("签名证书", "*.jks *.keystore *.p12 *.pfx")]
            selected = filedialog.askopenfilename(filetypes=filters)
            if selected:
                self.variables[key].set(selected)
        return choose

    def _choose_directory(self, key: str):
        def choose() -> None:
            selected = filedialog.askdirectory()
            if selected:
                self.variables[key].set(selected)
        return choose

    def _form_values(self) -> Dict[str, str]:
        values = {key: variable.get() for key, variable in self.variables.items()}
        values["orientation"] = self.orientation.get()
        return values

    def _save_config(self) -> None:
        name = self.config_name.get().strip()
        if not name:
            messagebox.showerror("配置错误", "请填写配置名称")
            return
        destination = filedialog.asksaveasfilename(
            title="保存配置文件",
            initialfile="{}.json".format(name),
            defaultextension=".json",
            filetypes=(("JSON 配置", "*.json"),),
        )
        if not destination:
            return
        try:
            self.store.save_file(Path(destination), config_from_form(self._form_values()))
            self.status.set("配置已保存到：{}（签名密码未保存）。".format(destination))
        except (ValueError, OSError) as error:
            messagebox.showerror("保存失败", str(error))

    def _load_config(self) -> None:
        source = filedialog.askopenfilename(
            title="选择配置文件",
            filetypes=(("JSON 配置", "*.json"), ("所有文件", "*.*")),
        )
        if not source:
            return
        try:
            config = self.store.load_file(Path(source))
        except ValueError:
            messagebox.showerror("加载失败", "配置文件无效")
            return
        for key, value in config.__dict__.items():
            if key == "orientation":
                self.orientation.set(value)
            elif key in self.variables:
                self.variables[key].set("" if key == "signing_password" or value is None else str(value))
        self.config_name.set(Path(source).stem)
        self.status.set("配置已加载，请重新输入签名密码。")

    def _start_build(self) -> None:
        try:
            config = config_from_form(self._form_values())
        except (ValueError, KeyError):
            messagebox.showerror("参数错误", "游戏 ID 必须为正整数，并填写所有出包参数。")
            return
        self.build_button.configure(state=tk.DISABLED)
        self.success_close_button.grid_remove()
        self.status.set("正在准备构建…")
        self.builder.start(config)

    def _close_window(self) -> None:
        self.root.destroy()

    def _handle_build_result(self, value) -> None:
        self.build_button.configure(state=tk.NORMAL)
        if value is None:
            self.success_close_button.grid_remove()
            self.status.set("构建失败：请检查参数、证书和模板。")
        elif value.success:
            self.status.set("构建完成：{}\n安装包信息：{}".format(
                value.apk_path,
                value.apk_path.with_suffix(".txt"),
            ))
            self.success_close_button.grid()
        else:
            self.success_close_button.grid_remove()
            self.status.set(value.message)

    def _poll_messages(self) -> None:
        while True:
            try:
                kind, value = self.messages.get_nowait()
            except queue.Empty:
                break
            if kind == "progress":
                self.status.set(value)
            else:
                self._handle_build_result(value)
        self.root.after(100, self._poll_messages)


def main() -> None:
    root = tk.Tk()
    PackagerWindow(root)
    root.mainloop()


if __name__ == "__main__":
    main()
