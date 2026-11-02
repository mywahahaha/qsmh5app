#!/usr/bin/env python3
"""使用钥匙串中已有的匹配身份归档并导出；不接收或记录签名密码。"""
import argparse
import datetime
import fnmatch
import hashlib
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    bundle_id = "com.un74meng.yjhcmc"
    profile = plistlib.loads(subprocess.run(["security", "cms", "-D", "-i", str(args.profile.resolve())], check=True, capture_output=True).stdout)
    entitlements = profile.get("Entitlements", {})
    team = profile["TeamIdentifier"][0]
    app_id = entitlements.get("application-identifier", "")
    if not fnmatch.fnmatchcase(team + "." + bundle_id, app_id):
        raise SystemExit("描述文件不匹配当前 Bundle ID")
    if profile["ExpirationDate"] <= datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None):
        raise SystemExit("描述文件已过期")
    identities = subprocess.run(["security", "find-identity", "-v", "-p", "codesigning"], check=True, capture_output=True, text=True).stdout
    fingerprint = next((hashlib.sha1(c).hexdigest().upper() for c in profile["DeveloperCertificates"]
                        if hashlib.sha1(c).hexdigest().upper() in identities), None)
    if not fingerprint:
        raise SystemExit("找不到描述文件匹配的有效签名身份；请先在钥匙串中导入配对私钥（通常是 .p12）。当前尚不需要输入签名密码。")
    if args.output.exists():
        raise SystemExit("输出目录已存在，拒绝覆盖")
    uuid = profile["UUID"]
    if not re.fullmatch(r"[A-Fa-f0-9-]{36}", uuid):
        raise SystemExit("描述文件 UUID 无效")
    if profile.get("ProvisionsAllDevices"):
        method = "enterprise"
    elif profile.get("ProvisionedDevices"):
        method = "debugging" if entitlements.get("get-task-allow") else "release-testing"
    else:
        raise SystemExit("当前脚本只导出可供设备测试的描述文件，不执行商店上传")
    developer = os.environ.get("DEVELOPER_DIR")
    if not developer:
        developer = next((str(path) for path in [Path("/Applications/Xcode.app/Contents/Developer"), Path("/Volumes/SSD/Applications/Xcode.app/Contents/Developer")]
                          if (path / "Platforms/iPhoneOS.platform").exists()), None)
    if not developer:
        raise SystemExit("请设置 DEVELOPER_DIR")
    env = dict(os.environ, DEVELOPER_DIR=developer)
    args.output.mkdir(parents=True)
    directory = Path.home() / "Library/MobileDevice/Provisioning Profiles"
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / (uuid + ".mobileprovision")
    original = destination.read_bytes() if destination.exists() else None
    archive = args.output.resolve() / "QSMH5App.xcarchive"
    try:
        shutil.copyfile(args.profile, destination)
        with (args.output / "archive.log").open("w") as log:
            subprocess.run(["xcodebuild", "-project", str(root / "QSMH5App.xcodeproj"), "-scheme", "QSMH5App-Integration", "-configuration", "Release",
                "-destination", "generic/platform=iOS", "-derivedDataPath", str(root / "artifacts/SignedDerivedData"), "-archivePath", str(archive),
                "CODE_SIGN_STYLE=Manual", "CODE_SIGNING_ALLOWED=YES", "DEVELOPMENT_TEAM=" + team, "CODE_SIGN_IDENTITY=" + fingerprint,
                "PROVISIONING_PROFILE_SPECIFIER=" + uuid, "archive"], env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        options = args.output / "ExportOptions.plist"
        options.write_bytes(plistlib.dumps({"method": method, "signingStyle": "manual", "teamID": team,
            "signingCertificate": fingerprint, "provisioningProfiles": {bundle_id: uuid}, "manageAppVersionAndBuildNumber": False}))
        with (args.output / "export.log").open("w") as log:
            subprocess.run(["xcodebuild", "-exportArchive", "-archivePath", str(archive), "-exportOptionsPlist", str(options.resolve()),
                            "-exportPath", str((args.output / "export").resolve())], env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(archive / "Products/Applications/QSMH5App.app")], check=True)
        print("已导出签名测试包：" + str((args.output / "export").resolve()))
        print("设备可安装范围由描述文件决定；Ad Hoc 仅适用于已登记设备。")
    finally:
        if original is None:
            destination.unlink(missing_ok=True)
        else:
            destination.write_bytes(original)


if __name__ == "__main__":
    main()
