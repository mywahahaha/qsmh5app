#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/environment.sh"
QSM_OUTPUT=${1:-"$QSM_IOS_ROOT/artifacts/全民红月_1.0.1_1_$(date +%Y%m%d-%H%M%S)"}
if [[ -e "$QSM_OUTPUT" ]]; then echo '目标目录已存在，拒绝覆盖。' >&2; exit 1; fi
mkdir -p "$QSM_OUTPUT"
xcodebuild -project "$QSM_IOS_ROOT/QSMH5App.xcodeproj" -scheme QSMH5App-Integration -configuration Release \
    -destination 'generic/platform=iOS' -derivedDataPath "$QSM_IOS_ROOT/artifacts/ReleaseDerivedData" \
    -archivePath "$QSM_OUTPUT/QSMH5App.xcarchive" CODE_SIGNING_ALLOWED=NO archive > "$QSM_OUTPUT/archive.log" 2>&1 || {
        tail -60 "$QSM_OUTPUT/archive.log"; exit 1;
    }
QSM_STAGING=$(mktemp -d "$QSM_IOS_ROOT/artifacts/unsigned-payload.XXXXXX")
trap 'rm -rf "$QSM_STAGING"' EXIT
"$QSM_PYTHON" "$QSM_IOS_ROOT/Scripts/verify_archive.py" "$QSM_OUTPUT/QSMH5App.xcarchive" > "$QSM_OUTPUT/构建摘要.json"
mkdir -p "$QSM_STAGING/Payload"
ditto "$QSM_OUTPUT/QSMH5App.xcarchive/Products/Applications/QSMH5App.app" "$QSM_STAGING/Payload/QSMH5App.app"
ditto -c -k --keepParent "$QSM_STAGING/Payload" "$QSM_OUTPUT/全民红月_未签名需重签.ipa"
printf '%s\n' '此 IPA 未签名，不能直接安装到 iPhone；需要匹配的签名身份和描述文件重签。' > "$QSM_OUTPUT/安装说明.txt"
shasum -a 256 "$QSM_OUTPUT/全民红月_未签名需重签.ipa" > "$QSM_OUTPUT/SHA256.txt"
echo "未签名归档及 IPA：$QSM_OUTPUT"
