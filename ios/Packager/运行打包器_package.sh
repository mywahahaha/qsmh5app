#!/bin/bash
set -euo pipefail
QSM_TOOL_ROOT=$(cd "$(dirname "$0")" && pwd)
source "$QSM_TOOL_ROOT/../Scripts/environment.sh"
exec "$QSM_PYTHON" "$QSM_TOOL_ROOT/Backend/build.py" "$@"
