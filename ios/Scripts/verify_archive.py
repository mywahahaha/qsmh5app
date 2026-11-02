#!/usr/bin/env python3
"""验证首个游戏归档的身份、方向、生产隔离和 SDK 资源，输出无凭据构建摘要。"""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import subprocess
import unicodedata


def channel_code(application_directory):
    # 与运行时一致：只读取 .app/packCode 的直接子目录；缺失或不可读返回空。
    try:
        apps = sorted(application_directory.iterdir(), key=lambda p: p.name)
    except OSError:
        return ""
    for app in apps:
        if not app.name.endswith('.app') or not app.is_dir() or app.is_symlink():
            continue
        folder = app / 'packCode'
        if not folder.is_dir() or folder.is_symlink():
            continue
        try:
            children = sorted(folder.iterdir(), key=lambda p: p.name)
        except OSError:
            continue
        for child in children:
            if child.is_dir() and not child.is_symlink() and child.name.strip() and not any(unicodedata.category(c) == 'Cc' for c in child.name):
                return child.name
    return ""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    apps = list((args.archive / "Products/Applications").glob("*.app"))
    if len(apps) != 1:
        raise SystemExit("归档必须恰好包含一个应用")
    app = apps[0]
    info = plistlib.loads((app / "Info.plist").read_bytes())
    assert info["CFBundleIdentifier"] == "com.un74meng.yjhcmc", "Bundle ID 错误"
    assert info["QSMGameID"] == "29", "gameId 错误"
    assert set(info["UISupportedInterfaceOrientations"]) == {"UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight"}, "方向错误"
    assert "NSAppTransportSecurity" not in info, "生产包不应携带本地 HTTP 例外"
    binary = app / info["CFBundleExecutable"]
    assert not any(marker in binary.read_bytes() for marker in [b"qsm-reset", b"fixture-h5-token", b"local-test-secret", b"localhost:18740"]), "生产包混入本地测试入口"
    assert not list(app.rglob("page.html")), "生产包混入 H5 测试页面"
    sdk = root / "Vendor/Aliyun/installed.json"
    sdk_info = json.loads(sdk.read_text()) if sdk.exists() else None
    if sdk_info:
        assert (app / "ATAuthSDK.bundle").is_dir(), "SDK 资源缺失"
        for name in ["ATAuthSDK", "YTXMonitor", "YTXOperators"]:
            assert (app / f"{name}-Privacy.bundle/PrivacyInfo.xcprivacy").is_file(), f"{name} 隐私清单缺失"
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, check=True).stdout.strip()
    signed = subprocess.run(["codesign", "-d", str(app)], capture_output=True).returncode == 0
    if signed:
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], capture_output=True, check=True)
    summary = {"bundleId": info["CFBundleIdentifier"], "gameId": int(info["QSMGameID"]), "version": info["CFBundleShortVersionString"],
        "build": info["CFBundleVersion"], "displayName": info.get("CFBundleDisplayName"), "orientation": "landscape",
        "gameURL": info.get("QSMInitialGameURL"), "channelCode": channel_code(app.parent),
        "gitBase": revision, "includesUncommittedDevelopment": True,
        "signing": "已签名；归档验签通过，导出 IPA 须单独验签" if signed else "未签名；需要匹配身份重签后安装",
        "sdkArchive": sdk_info.get("archive") if sdk_info else None, "sdkArchiveSHA256": sdk_info.get("sha256") if sdk_info else None,
        "executableSHA256": hashlib.sha256(binary.read_bytes()).hexdigest()}
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
