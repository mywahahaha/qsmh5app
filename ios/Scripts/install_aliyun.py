#!/usr/bin/env python3
"""从指定官方静态 SDK ZIP 安装二进制；不读取账号或认证密钥。"""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import shutil
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    ios = Path(__file__).resolve().parents[1]
    destination = ios / "Vendor/Aliyun/SDK"
    if destination.exists():
        raise SystemExit("SDK 目录已存在；请先检查当前版本并手工移走，避免覆盖。")
    with tempfile.TemporaryDirectory(prefix="qsm-aliyun-") as temporary:
        root = Path(temporary)
        with zipfile.ZipFile(args.archive) as archive:
            # 只提取 SDK，不带入 Demo、其他项目资料或任何配置。
            for item in archive.infolist():
                parts = Path(item.filename).parts
                if len(parts) < 3 or parts[1] != "SDK" or ".." in parts:
                    continue
                archive.extract(item, root)
        candidates = list(root.glob("*/SDK"))
        if len(candidates) != 1:
            raise SystemExit("压缩包 SDK 目录不符合预期")
        source = candidates[0]
        libraries = {}
        for name in ("ATAuthSDK", "YTXMonitor", "YTXOperators"):
            framework = source / "xcframeworks" / f"{name}.xcframework"
            info = plistlib.loads((framework / "Info.plist").read_bytes())
            libraries[name] = [{k: entry.get(k) for k in ("LibraryIdentifier", "SupportedPlatformVariant", "SupportedArchitectures")}
                               for entry in info["AvailableLibraries"]]
            for identifier in ("ios-arm64", "ios-arm64_x86_64-simulator"):
                if not (framework / identifier / f"{name}.framework" / name).is_file():
                    raise SystemExit(f"缺少 {name} 的 {identifier} 切片")
        bundles = list(source.glob("xcframeworks/ATAuthSDK.xcframework/ios-arm64/ATAuthSDK.framework/ATAuthSDK.bundle"))
        if not bundles:
            raise SystemExit("缺少 ATAuthSDK.bundle")
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(source, destination)
    digest = hashlib.sha256(args.archive.read_bytes()).hexdigest()
    (destination.parent / "installed.json").write_text(json.dumps({"archive": args.archive.name, "sha256": digest, "libraries": libraries}, ensure_ascii=False, indent=2) + "\n")
    (ios / "Config/Aliyun.local.xcconfig").write_text('''// 由 install_aliyun.py 生成；本机文件，不提交。
QSM_ENABLE_ALIYUN = YES
QSM_ALIYUN_CONDITION = QSM_ALIYUN
OTHER_LDFLAGS = $(inherited) -ObjC -framework ATAuthSDK -framework YTXMonitor -framework YTXOperators -framework Network -framework CoreTelephony -framework SystemConfiguration -framework Security -framework AdSupport -lresolv -lc++ -lz
FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*] = $(inherited) $(PROJECT_DIR)/Vendor/Aliyun/SDK/xcframeworks/ATAuthSDK.xcframework/ios-arm64 $(PROJECT_DIR)/Vendor/Aliyun/SDK/xcframeworks/YTXMonitor.xcframework/ios-arm64 $(PROJECT_DIR)/Vendor/Aliyun/SDK/xcframeworks/YTXOperators.xcframework/ios-arm64
FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*] = $(inherited) $(PROJECT_DIR)/Vendor/Aliyun/SDK/xcframeworks/ATAuthSDK.xcframework/ios-arm64_x86_64-simulator $(PROJECT_DIR)/Vendor/Aliyun/SDK/xcframeworks/YTXMonitor.xcframework/ios-arm64_x86_64-simulator $(PROJECT_DIR)/Vendor/Aliyun/SDK/xcframeworks/YTXOperators.xcframework/ios-arm64_x86_64-simulator
''')
    print(f"SDK 已安装：{args.archive.name}\nSHA-256：{digest}")


if __name__ == "__main__":
    main()
