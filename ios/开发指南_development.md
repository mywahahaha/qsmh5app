# iOS开发指南

首个游戏：`gameId=29`，Bundle ID `com.un74meng.yjhcmc`，版本 `1.0.1`，横屏。最低部署目标暂为 iOS 16；最低系统的设备兼容仍待安装测试。

## 打开与本地运行

用 Xcode 打开 `QSMH5App.xcodeproj`。默认共享 Scheme `QSMH5App` 使用 `Debug-Local`，本地 Bundle ID 增加 `.local`，与正式包隔离。

在仓库根目录运行：

```bash
ios/Scripts/run_local.sh -qsm-reset
```

脚本会启动本地 HTTP/H5 服务、构建并安装模拟器应用。保持终端运行；Ctrl-C 关闭该脚本启动的测试服务。`-qsm-reset` 只存在于本地构建，清理本地测试状态；正式包不读取此参数。

本地服务固定监听 `127.0.0.1:18740`，页面使用 `localhost`，不能从外网访问。端口被占用时脚本停止，不复用或结束其他进程。

## 测试

```bash
ios/Scripts/run_tests.sh
node --test ios/Tests/*.cjs 示例/bridge-script.test.js 示例/qsm-jsbridge.test.js 示例/external-app-bridge.test.js
```

可设置 `QSM_SIMULATOR_ID` 指定实际模拟器 UUID。脚本自动准备服务并运行 XCTest/XCUITest；日志、HTTP 接收记录和 `.xcresult` 位于 `artifacts/test-run.*`。只关闭该次任务启动的模拟器和服务。

本地服务提供初始化失败、缺 code、无需协议、Token 失效等场景。XCUITest 用 `QSM_LOCAL_SCENARIO` 选择它们。认证 Gateway 用单元测试替身验证展示、兑换和晚回调，真实 SDK 不编入 `Debug-Local`。

## 真实服务构建

`QSMH5App-Integration` 使用 `Debug-Integration`；`Release` 用于设备归档。固定初始化地址为 `https://box.74meng.com/h5_app/init`，原生业务请求附带六项 Header，渠道码默认空。没有全局 HTTP/TLS 放行。

包内游戏地址是用户指定的：

```text
https://box.74meng.com/h5/play?gid=29&sid=1&cid=218&app=1&xid=0
```

它只替换游戏初始 URL，必须与初始化响应的游戏 origin 完全一致；登录、协议和福利仍取初始化配置。服务端当前返回的游戏 URL 参数不同，未直接覆盖本次用户指定地址。

`Config/Base.xcconfig` 保存公共非敏感身份。团队/手动签名参数放在被忽略的 `Config/LocalOverrides.xcconfig`，可参考 `.example.xcconfig`。模拟器使用独立临时签名与模拟器 Keychain 组，不借用其他 Apple 团队的身份。

## iOS 渠道目录读取

当前构建号为 `3`。渠道码不再读取 Info.plist 或编译变量：启动时从主 Bundle 的父目录枚举所有直接下级 `.app` 目录，依次找 `packCode` 的直接子目录，取目录名作为渠道码。它对应 IPA 中的 `Payload/*.app/packCode/渠道目录名/`，不依赖 `QSMH5App.app` 这个固定名称。

例如 `Payload/QSMH5App.app/packCode/1-218/` 返回 `1-218`。缺目录、读取失败、路径是文件、符号链接及非法 Header 名称均无异常地回退为空；不阻塞其他流程。多个候选按应用目录名、渠道目录名升序选择首个有效目录。只读取直接子目录，不扫描任意深层资源。

`RuntimeConfiguration` 将读取结果存入 `GameIdentity.packageCode`，`getGameInfo.data.packageCode` 与原生请求 `Packagecode` Header 共用该值；H5 请求通过通用包装读取信息后设置 Header。未分包母包两处均为空。

渠道修复历史母包：`artifacts/交付_20261102_渠道读取修复_AdHoc/全民红月_1.0.1_2_母包_AdHoc.ipa`。悬浮球新版交付见下节。**请使用新版母包重新执行现有分包脚本**，旧版二进制仅新增目录不会获得新逻辑。Ad Hoc 仅限 profile 中登记的 6 台设备；本轮未执行真机安装。

H5 公共调用与当前能力差异见 [JSBridge 清单第 8 节](../JSBridge方法清单_jsbridge.md)。已对齐登录成功、窗口开关/关闭页面的 `data=true`，以及存储输入的 JS 归一化；角色、第三方应用和隐私撤回仍按清单处理平台能力差异。

## 悬浮球（构建 3）

已实现同 Android 的游戏就绪后显示、纵向拖动、点击福利、福利置顶暂隐/返回恢复、Bridge `setFloatingBall` 与心跳 `floating_ball`。H5 使用同一调用；iOS 坐标为 pt，容器采用 safeArea。尺寸和位置字段优先级、完整测试及交付信息见 [悬浮球记录](../docs/iOS悬浮球需求与实现_ios-floating-ball.md)。心跳运营清单 `popup_pages` 尚未实现。

最新签名母包：`artifacts/交付_20261102_悬浮球_AdHoc/全民红月_1.0.1_3_母包_AdHoc.ipa`。最终导出 IPA 已严格验签，通过身份、权限、生产隔离及 SDK 资源检查；请以此重新分包，安装范围仍为 profile 登记的 6 台设备。未执行真机安装。

## 阿里云 SDK

```bash
/usr/local/opt/python@3.12/bin/python3.12 ios/Scripts/install_aliyun.py /安全路径/numberAuthSDK_APP_iOS_v2.14.19_operator_ui_log_static.zip
```

安装脚本验证并提取三个静态 XCFramework，生成本机 `Aliyun.local.xcconfig`；SDK 二进制、本机配置和安装记录不入库。已安装目录存在时拒绝覆盖。详情见[接入说明](Vendor/Aliyun/接入说明_integration.md)。

没有 SDK 或初始化关闭一键登录时走 H5；SDK 已安装但密钥、业务协议或兑换地址不可用时也回退 H5。不会复用 Android 密钥。Release 控制台日志显式关闭。

## 归档与签名

```bash
ios/Scripts/doctor.sh
ios/Scripts/archive_unsigned.sh
```

输出归档、dSYM、未签名 IPA、安装说明、构建摘要和 SHA-256。**未签名 IPA 不能直接安装到 iPhone，需要匹配身份重签。** 脚本验证 Bundle ID、游戏身份、方向、生产测试隔离与 SDK 资源。

钥匙串已有匹配证书与私钥后，可使用：

```bash
/usr/local/opt/python@3.12/bin/python3.12 ios/Scripts/export_signed.py \
  --profile 游戏出包/iOS/证书/h5App.mobileprovision \
  --output ios/artifacts/签名测试包
```

脚本先验证身份、有效期及 Bundle ID，不接收密码；无有效身份时在创建输出和安装 profile 前停止。需要导入 `.p12` 时通过系统钥匙串输入密码。导出用的 profile 临时放入标准目录，结束后恢复原状态；不撤销证书或自动管理账号。

本轮用户选择自行安装测试，因此没有执行真机、SIM 取号或覆盖安装。当前提供的 profile 为 Ad Hoc，安装范围是其中登记的设备。

2026-11-02 更新：`游戏出包/iOS/证书/` 中的新分发证书与 p12 私钥已确认配对，钥匙串身份有效。`h5App.mobileprovision` 绑定此证书，Team `7BNPHDL54G`、App ID `7BNPHDL54G.com.un74meng.*`，包含 6 台登记设备，有效期至 2027-11-02。已成功归档并导出 `artifacts/交付_20261102_S1_AdHoc/全民红月_1.0.1_AdHoc.ipa`，最终 IPA 严格验签、签名叶证书、嵌入描述文件、权限及生产资源隔离检查通过。安装说明、SHA-256 和构建摘要位于同目录。按用户要求未执行真机安装。结果见[首期联调记录](../docs/iOS首期开发与联调记录_ios-bootstrap-development.md)。

## 目录与后续工作

- `Core/`：严格配置解析、HTTP、原子状态文件、Keychain、启动与会话协调。
- `Features/`：WKWebView/Bridge、真实 SDK Gateway。
- `Fixtures/`：只供本地测试的 HTTP/H5 页面，不打入 App。
- `Tests/`、`UITests/`：规则/替身和真实模拟器 UI 回归。
- `Scripts/`：环境、运行、测试、SDK 安装、归档和签名检查。

尚未完成角色、心跳运营窗口同步、原生更新 UI、常亮、正式 SDK 品牌授权弹窗、图标与闪图完整资产映射，以及多游戏出包。本轮明确返回未支持方法的错误；启用更新的配置会提示当前阶段未接入，不静默跳过强制更新。

工程文件已提供，日常构建无需 Ruby/CocoaPods。以后新增 Swift 文件，可手工加入 target，或用安装了 `xcodeproj` 的 Ruby 执行 `Scripts/generate_project.rb` 重新生成工程；重新生成会更新工程 UUID。
