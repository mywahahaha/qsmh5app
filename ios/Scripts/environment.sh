#!/bin/bash
# 可被其他脚本 source；不修改全机 xcode-select。
set -euo pipefail
QSM_IOS_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z "${DEVELOPER_DIR:-}" ]]; then
    QSM_SELECTED=$(xcode-select -p)
    if [[ -d "$QSM_SELECTED/Platforms/iPhoneOS.platform" ]]; then
        export DEVELOPER_DIR="$QSM_SELECTED"
    elif [[ -d /Applications/Xcode.app/Contents/Developer ]]; then
        export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
    elif [[ -d /Volumes/SSD/Applications/Xcode.app/Contents/Developer ]]; then
        export DEVELOPER_DIR=/Volumes/SSD/Applications/Xcode.app/Contents/Developer
    else
        echo '请设置 DEVELOPER_DIR，指向完整 Xcode 的 Contents/Developer。' >&2
        exit 1
    fi
fi
if [[ -x /usr/local/opt/python@3.12/bin/python3.12 ]]; then
    QSM_PYTHON=/usr/local/opt/python@3.12/bin/python3.12
elif [[ -x /opt/homebrew/opt/python@3.12/bin/python3.12 ]]; then
    QSM_PYTHON=/opt/homebrew/opt/python@3.12/bin/python3.12
else
    QSM_PYTHON=$(command -v python3)
fi
"$QSM_PYTHON" -c 'import sys; assert sys.version_info >= (3, 11), "请安装 Python 3.11+"'
mkdir -p "$QSM_IOS_ROOT/artifacts"
