#!/bin/bash
set -euo pipefail
QSM_TOOL_ROOT=$(cd "$(dirname "$0")" && pwd)
source "$QSM_TOOL_ROOT/../Scripts/environment.sh"
QSM_TOOL_OUTPUT=${1:-"$QSM_IOS_ROOT/artifacts/iOS游戏打包器.app"}
if [[ -e "$QSM_TOOL_OUTPUT" ]]; then echo '输出已存在，请选择新目录。' >&2; exit 1; fi
QSM_TOOL_STAGING=$(mktemp -d "$QSM_IOS_ROOT/artifacts/packager-app.XXXXXX")
trap 'rm -rf "$QSM_TOOL_STAGING"' EXIT
mkdir -p "$QSM_TOOL_STAGING/Contents/MacOS" "$QSM_TOOL_STAGING/Contents/Resources/Backend"
xcrun --sdk macosx swiftc -parse-as-library -O -target "$(uname -m)-apple-macosx13.0" \
    "$QSM_TOOL_ROOT/PackagerApp.swift" -o "$QSM_TOOL_STAGING/Contents/MacOS/QSMH5Packager"
cp "$QSM_TOOL_ROOT/Backend/build.py" "$QSM_TOOL_ROOT/Backend/images.swift" "$QSM_TOOL_STAGING/Contents/Resources/Backend/"
"$QSM_PYTHON" - "$QSM_TOOL_STAGING/Contents/Info.plist" "$QSM_IOS_ROOT" "$QSM_PYTHON" <<'PY'
import plistlib,sys
from pathlib import Path
Path(sys.argv[1]).write_bytes(plistlib.dumps({'CFBundleIdentifier':'com.qishimeng.ios.packager',
 'CFBundleName':'iOS游戏打包器','CFBundleExecutable':'QSMH5Packager','CFBundlePackageType':'APPL',
 'CFBundleShortVersionString':'1.0.0','CFBundleVersion':'1','LSMinimumSystemVersion':'13.0',
 'NSHighResolutionCapable':True,'QSMSourceRoot':sys.argv[2],'QSMPython':sys.argv[3]}))
PY
codesign --force --sign - "$QSM_TOOL_STAGING"
mkdir -p "$(dirname "$QSM_TOOL_OUTPUT")"
mv "$QSM_TOOL_STAGING" "$QSM_TOOL_OUTPUT"
echo "已生成：$QSM_TOOL_OUTPUT"
