#!/bin/bash
set -euo pipefail
if [[ "${QSM_ENABLE_ALIYUN:-NO}" != YES ]]; then exit 0; fi
QSM_SDK_ROOT="$PROJECT_DIR/Vendor/Aliyun/SDK/xcframeworks"
QSM_SLICE=ios-arm64
if [[ "$PLATFORM_NAME" == iphonesimulator ]]; then QSM_SLICE=ios-arm64_x86_64-simulator; fi
QSM_RESOURCES="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
ditto "$QSM_SDK_ROOT/ATAuthSDK.xcframework/$QSM_SLICE/ATAuthSDK.framework/ATAuthSDK.bundle" "$QSM_RESOURCES/ATAuthSDK.bundle"
for QSM_LIBRARY in ATAuthSDK YTXMonitor YTXOperators; do
    QSM_PRIVACY="$QSM_SDK_ROOT/$QSM_LIBRARY.xcframework/$QSM_SLICE/$QSM_LIBRARY.framework/PrivacyInfo.xcprivacy"
    if [[ -f "$QSM_PRIVACY" ]]; then
        mkdir -p "$QSM_RESOURCES/$QSM_LIBRARY-Privacy.bundle"
        cp "$QSM_PRIVACY" "$QSM_RESOURCES/$QSM_LIBRARY-Privacy.bundle/PrivacyInfo.xcprivacy"
        /usr/libexec/PlistBuddy -c 'Clear dict' "$QSM_RESOURCES/$QSM_LIBRARY-Privacy.bundle/Info.plist" >/dev/null 2>&1
        /usr/libexec/PlistBuddy -c "Add :CFBundleIdentifier string com.qsmh5app.privacy.$QSM_LIBRARY" "$QSM_RESOURCES/$QSM_LIBRARY-Privacy.bundle/Info.plist"
        /usr/libexec/PlistBuddy -c 'Add :CFBundlePackageType string BNDL' "$QSM_RESOURCES/$QSM_LIBRARY-Privacy.bundle/Info.plist"
    fi
done
