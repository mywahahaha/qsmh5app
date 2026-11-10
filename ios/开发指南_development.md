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

本地服务提供初始化失败、缺 code、无需协议、Token 失效、首次联网等待等场景。XCUITest 用 `QSM_LOCAL_SCENARIO` 选择它们。认证 Gateway 用单元测试替身验证展示、兑换和晚回调，真实 SDK 默认不编入 `Debug-Local`。

## 真实服务构建

`QSMH5App-Integration` 使用 `Debug-Integration`；`Release` 用于设备归档。固定初始化地址为 `https://box.74meng.com/h5_app/init`，原生业务请求附带八项 Header，渠道码默认空。没有全局 HTTP/TLS 放行。

包内游戏地址是用户指定的：

```text
https://box.74meng.com/h5/play?gid=29&sid=1&cid=218&app=1&xid=0
```

它只替换游戏初始 URL，必须与初始化响应的游戏 origin 完全一致；登录、协议和福利仍取初始化配置。服务端当前返回的游戏 URL 参数不同，未直接覆盖本次用户指定地址。

`Config/Base.xcconfig` 保存公共非敏感身份。团队/手动签名参数放在被忽略的 `Config/LocalOverrides.xcconfig`，可参考 `.example.xcconfig`。模拟器使用独立临时签名与模拟器 Keychain 组，不借用其他 Apple 团队的身份。

## iOS 渠道目录读取

渠道码不读取 Info.plist 或编译变量：构建 8 起，安装后直接从当前 `Bundle.main.bundleURL/packCode/` 枚举直接子目录，取目录名作为渠道码，不依赖 `QSMH5App.app` 这个固定名称。不能先枚举主 Bundle 的父目录，真机沙盒不保证允许列出安装容器。离线读取已解压 IPA 时，`read(payloadDirectory:)` 仍可枚举 `Payload/*.app/packCode/渠道目录名/`。

例如 `Payload/QSMH5App.app/packCode/1-218/` 返回 `1-218`。缺目录、读取失败、路径是文件、符号链接及非法 Header 名称均无异常地回退为空；不阻塞其他流程，也不创建任何目录。多个渠道候选按目录名升序选择首个有效目录；运行时只读取自身应用，不读取同级应用。离线枚举多个应用时按应用名、渠道名排序。只读取直接子目录，不扫描任意深层资源。

`RuntimeConfiguration` 将读取结果存入 `GameIdentity.packageCode`，`getGameInfo.data.packageCode` 与原生请求 `Packagecode` Header 共用该值；H5 请求通过通用包装读取信息后设置 Header。未分包母包两处均为空。原生接口另附 `Notchscreen` Header：iPhone 当前安全区域显示屏幕开孔时为 `1`，否则为 `0`。JS Bridge 的 `getGameInfo.data.notchScreen` 使用同一判断，返回数字 `1` 或 `0`。H5 可用它选择刘海屏布局；它不替代 H5 自身的安全区域适配。

屏幕方向使用 `horizontal` / `vertical` 两个值。`getGameInfo.data.screenMode`、原生请求和公共 Axios 包装的 `Screenmode` Header 都读取当前界面方向。`changeScreenmode({mode})` 可强制切换横竖屏；目标方向与当前方向相同时直接返回 `data=true`，非法参数返回 `code=1001,msg="参数无效",data=false`。出包器仍按游戏配置决定初始方向，但最终包同时声明横竖屏能力，以便运行中切换。

### 原生版本更新

隐私同意后、登录前读取初始化响应的 `updateInfo`。有效配置的 `open=1` 即显示原生更新弹窗，是否需要提示由服务端决定；客户端不再额外比较目标版本。宽度和高度纯数字按 dp（1 dp = 1 pt）计算，带 `%` 按屏幕比例计算，`height="auto"` 按内容自适应。标题、说明、版本、日期及强制标记沿用服务端字段，弹窗使用黄色头图、绿色按钮和 45% 黑色遮罩。普通更新可关闭并登录；强制更新不显示关闭按钮，从外部入口返回后仍可再次打开。点击“立即更新”由系统浏览器打开服务端提供的 HTTP/HTTPS iOS 更新说明或安装引导页。URL 无效、指向 APK 或系统拒绝打开时结束本次更新检查并继续登录。iOS 不下载或安装 APK，也不直接安装 IPA；服务端应按 `Devicetype=ios` 下发 iOS 专用更新入口。极小的固定宽高不会阻止弹窗，但可能无法容纳完整内容，需由服务端提供可用尺寸。

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

尚未完成角色、心跳运营窗口同步、原生更新 UI 和常亮。本轮明确返回未支持方法的错误；启用更新的配置会提示当前阶段未接入，不静默跳过强制更新。SDK 品牌授权弹窗已在构建 7 接入，真机视觉与取号交互仍需用户复验。

工程文件已提供，日常构建无需 Ruby/CocoaPods。以后新增 Swift 文件，可手工加入 target，或用安装了 `xcodeproj` 的 Ruby 执行 `Scripts/generate_project.rb` 重新生成工程；重新生成会更新工程 UUID。


## 图片与多游戏打包器（构建 4）

2026-11-03 已接入初始化 `bgImage` 背景、本地闪图、透明容器与首帧淡入；正式图标和闪图按每游戏配置生成。默认系统启动画面为静态黑底；等待初始化联网期间保留应用内闪图，初始化成功后采用 1.2–5 秒时序。需要透出底图的 H5 页面自身背景应透明。

Mac 图形工具位于 `artifacts/iOS游戏打包器_正式.app`，支持选择游戏 ID、图标、闪图、方向、签名身份及描述文件。使用[打包器指南](Packager/使用指南_usage.md)开始操作；[实施记录](../docs/iOS图片与打包器实施记录_ios-assets-packager-development.md)列出验证结果与当前边界。

原归档/签名命令现调用通用后端，可传 `--config`；默认使用 `Packager/游戏29示例_game29.json`。版本为 1.0.1、构建号 4，当前描述文件登记 7 台设备；上文构建 3 及更早路径与 6 台记录为历史结果。新包以本次摘要和安装说明为准。


空渠道目录修正：渠道码为空时不打入 `packCode/` 根目录或任何渠道子目录。新母包见 `artifacts/交付_20261103_空渠道无目录_AdHoc/`；Mac 工具内置后端已同步更新。

## 首次联网及授权方向修复（构建 6）

用户已确认构建 5 真机安装和桌面图标正常，并反馈首次联网错误白屏、同意隐私后及后续启动闪退。本轮增加初始化网络等待与自动重试，阿里云授权页方向改为与包内支持方向一致；具体证据、测试、日志提取及待真机复验项见[修复记录](../docs/iOS首次联网与授权闪退修复_ios-startup-auth-fix.md)。

构建 6 沿用用户的游戏 29、横屏、空渠道、图标和横版闪图配置；`game_url` 为空，游戏地址取初始化接口。本节为最新修复包口径，上文构建 4 的默认示例及历史地址说明保留作参考；用户保存的构建 5 配置未覆盖。

## 授权品牌 UI 与 H5 真全屏（构建 7）

已将文档中的弧形红头、骑士盟 Logo、橙色主按钮、协议和关闭操作接入 SDK 公开配置，按服务端百分比布局并检查紧凑窗口完整性。所有业务 H5 禁用原生页面拖动、回弹、缩放和滚动条；宽高同时为 `100%` 时覆盖整个宿主窗口，H5 自行适配安全区。实现、测试、真机复验及最新 IPA 见[授权 UI 与全屏修复记录](../docs/iOS授权品牌UI与全屏容器修复_ios-auth-ui-fullscreen.md)。

## iOS 14.4.2 兼容（构建 9）

当前工程最低运行系统改为 iOS 14.0，已同步公共配置、工程和工程生成脚本。iOS 15/16 才有的 Bridge、图片下载和时钟 API 已替换或加可用性分支；新的签名包内嵌 2026-11-04 更新的 Ad Hoc 描述文件及 Swift 并发回移运行库。游戏 29 的空渠道母包与 `1-218` 渠道包、测试证据和 iPhone 8 Plus 复验清单见[兼容实施记录](../docs/iOS14兼容实施记录_ios14-compatibility.md)。

## 悬浮球 `right: 0` 屏幕贴边（游戏 92 构建 3）

横屏时悬浮球原先以安全区域为整个容器，右侧安全区导致 `right: 0` 仍离屏幕边缘较远。现在水平使用完整窗口，纵向继续使用安全区域；对应本地 UI 断言、截图和“天尊传奇”签名包见[悬浮球记录](../docs/iOS悬浮球需求与实现_ios-floating-ball.md)。
