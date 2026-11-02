#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/environment.sh"
QSM_RUN_DIR=$(mktemp -d "$QSM_IOS_ROOT/artifacts/local-run.XXXXXX")
QSM_SERVER_PID=
trap 'if [[ -n "$QSM_SERVER_PID" ]]; then kill "$QSM_SERVER_PID" 2>/dev/null || true; fi' EXIT
"$QSM_PYTHON" "$QSM_IOS_ROOT/Fixtures/server.py" --ready-file "$QSM_RUN_DIR/ready" > "$QSM_RUN_DIR/fixture.log" 2>&1 &
QSM_SERVER_PID=$!
for QSM_TRY in {1..30}; do
    [[ -f "$QSM_RUN_DIR/ready" ]] && break
    kill -0 "$QSM_SERVER_PID" 2>/dev/null || { cat "$QSM_RUN_DIR/fixture.log"; exit 1; }
    sleep 0.2
done
[[ -f "$QSM_RUN_DIR/ready" ]] || { echo '本地服务未就绪'; exit 1; }
QSM_SIMULATOR_ID=${QSM_SIMULATOR_ID:-$(xcrun simctl list devices available --json | "$QSM_PYTHON" -c 'import json,sys; print(next(d["udid"] for group in json.load(sys.stdin)["devices"].values() for d in group if d.get("isAvailable") and d["name"].startswith("iPhone")))')}
xcrun simctl boot "$QSM_SIMULATOR_ID" 2>/dev/null || true
xcrun simctl bootstatus "$QSM_SIMULATOR_ID" -b > "$QSM_RUN_DIR/simulator.log" 2>&1
xcodebuild -project "$QSM_IOS_ROOT/QSMH5App.xcodeproj" -scheme QSMH5App -configuration Debug-Local \
    -destination "platform=iOS Simulator,id=$QSM_SIMULATOR_ID" -derivedDataPath "$QSM_IOS_ROOT/artifacts/DerivedData" CODE_SIGN_IDENTITY=- CODE_SIGNING_ALLOWED=YES build > "$QSM_RUN_DIR/build.log" 2>&1
xcrun simctl install "$QSM_SIMULATOR_ID" "$QSM_IOS_ROOT/artifacts/DerivedData/Build/Products/Debug-Local-iphonesimulator/QSMH5App.app"
open -a "$DEVELOPER_DIR/Applications/Simulator.app" --args -CurrentDeviceUDID "$QSM_SIMULATOR_ID"
echo '本地联调运行中，Ctrl-C 结束测试服务。'
xcrun simctl launch --console "$QSM_SIMULATOR_ID" com.un74meng.yjhcmc.local "$@"
wait "$QSM_SERVER_PID"
