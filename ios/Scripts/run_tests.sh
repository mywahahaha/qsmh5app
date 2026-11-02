#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/environment.sh"
QSM_RUN_DIR=$(mktemp -d "$QSM_IOS_ROOT/artifacts/test-run.XXXXXX")
QSM_FIXTURE_PID=
QSM_BOOTED_BY_TASK=NO
cleanup() {
    if [[ -n "$QSM_FIXTURE_PID" ]]; then kill "$QSM_FIXTURE_PID" 2>/dev/null || true; wait "$QSM_FIXTURE_PID" 2>/dev/null || true; fi
    if [[ "$QSM_BOOTED_BY_TASK" == YES ]]; then xcrun simctl shutdown "$QSM_SIMULATOR_ID" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
if [[ -z "${QSM_SIMULATOR_ID:-}" ]]; then
    QSM_SIMULATOR_ID=$(xcrun simctl list devices available --json | "$QSM_PYTHON" -c 'import json,sys; devices=[d for group in json.load(sys.stdin)["devices"].values() for d in group if d.get("isAvailable") and d["name"].startswith("iPhone")]; assert devices,"没有可用 iPhone 模拟器"; print(devices[0]["udid"])')
fi
QSM_STATE=$(xcrun simctl list devices available --json | "$QSM_PYTHON" -c 'import json,sys; uid=sys.argv[1]; print(next(d["state"] for group in json.load(sys.stdin)["devices"].values() for d in group if d["udid"]==uid))' "$QSM_SIMULATOR_ID")
if [[ "$QSM_STATE" != Booted ]]; then
    xcrun simctl boot "$QSM_SIMULATOR_ID"
    QSM_BOOTED_BY_TASK=YES
fi
xcrun simctl bootstatus "$QSM_SIMULATOR_ID" -b > "$QSM_RUN_DIR/simulator.log" 2>&1
"$QSM_PYTHON" "$QSM_IOS_ROOT/Fixtures/server.py" --port 18740 --ready-file "$QSM_RUN_DIR/ready" > "$QSM_RUN_DIR/fixture.log" 2>&1 &
QSM_FIXTURE_PID=$!
for QSM_ATTEMPT in {1..30}; do
    if [[ -f "$QSM_RUN_DIR/ready" ]]; then break; fi
    if ! kill -0 "$QSM_FIXTURE_PID" 2>/dev/null; then cat "$QSM_RUN_DIR/fixture.log"; exit 1; fi
    sleep 0.2
done
if [[ ! -f "$QSM_RUN_DIR/ready" ]]; then echo '本地服务未启动'; exit 1; fi
echo "测试设备：$QSM_SIMULATOR_ID"
echo "测试记录：$QSM_RUN_DIR"
xcodebuild -project "$QSM_IOS_ROOT/QSMH5App.xcodeproj" -scheme QSMH5App -configuration Debug-Local \
    -destination "platform=iOS Simulator,id=$QSM_SIMULATOR_ID" -derivedDataPath "$QSM_IOS_ROOT/artifacts/DerivedData" \
    -resultBundlePath "$QSM_RUN_DIR/Tests.xcresult" -parallel-testing-enabled NO \
    CODE_SIGN_IDENTITY=- CODE_SIGNING_ALLOWED=YES test "$@" > "$QSM_RUN_DIR/xcodebuild.log" 2>&1 || {
        tail -80 "$QSM_RUN_DIR/xcodebuild.log"; exit 1;
    }
curl --fail --silent --show-error http://localhost:18740/__requests > "$QSM_RUN_DIR/fixture-requests.json"
tail -24 "$QSM_RUN_DIR/xcodebuild.log"
