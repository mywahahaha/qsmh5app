#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/environment.sh"
xcodebuild -version
xcrun --sdk iphoneos --show-sdk-version
security find-identity -v -p codesigning
"$QSM_PYTHON" - "$QSM_IOS_ROOT" <<'PY'
import hashlib,json,plistlib,subprocess,sys
from pathlib import Path
root=Path(sys.argv[1]); profile=root.parent/'游戏出包/iOS/证书/h5App.mobileprovision'
if profile.exists():
    result=subprocess.run(['security','cms','-D','-i',str(profile)],capture_output=True,check=True)
    info=plistlib.loads(result.stdout)
    identities=subprocess.run(['security','find-identity','-v','-p','codesigning'],capture_output=True,text=True,check=True).stdout
    hashes=[hashlib.sha1(cert).hexdigest().upper() for cert in info.get('DeveloperCertificates',[])]
    print(json.dumps({'profile':info.get('Name'),'team':info.get('TeamIdentifier'),'app_id':info.get('Entitlements',{}).get('application-identifier'),'expires':str(info.get('ExpirationDate')),'debuggable':info.get('Entitlements',{}).get('get-task-allow'),'device_count':len(info.get('ProvisionedDevices',[])),'matching_valid_signing_identity':any(h in identities for h in hashes)},ensure_ascii=False,indent=2))
installed=root/'Vendor/Aliyun/installed.json'
print('阿里云 SDK：'+(installed.read_text() if installed.exists() else '未安装，可使用 H5 登录；见安装脚本'))
PY
