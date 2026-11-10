#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/environment.sh"
QSM_OUTPUT=${1:-"$QSM_IOS_ROOT/artifacts/未签名_$(date +%Y%m%d-%H%M%S)"}
QSM_CONFIG=${2:-"$QSM_IOS_ROOT/Packager/游戏29示例_game29.json"}
exec "$QSM_PYTHON" "$QSM_IOS_ROOT/Packager/Backend/build.py" build --unsigned \
    --source "$QSM_IOS_ROOT" --config "$QSM_CONFIG" --output "$QSM_OUTPUT"
