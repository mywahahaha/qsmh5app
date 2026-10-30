# 安卓工程构建与联调指南

## 1. 当前交付状态

2026-10-28 初始化更新配置已修订为 `open/width/height/popup/force/title/time/desc/version/url`，详见 [初始化接口字段说明](初始化接口字段说明_initialization-api.md)。`popup=1` 外部浏览器更新、`popup=0` 内部下载安装；原生弹窗及主流程已接线，本次验证与真机验收边界见下方“原生版本更新验证”。

| 模块 | 当前状态 | 验证边界 |
| --- | --- | --- |
| Android Gradle 工程 | 已建立，Kotlin/JDK 17，最低 API 23 | 单元测试与 Debug 构建；尚未完成真机 WebView 联调 |
| 原生业务模块 | 初始化、隐私同意、原生版本更新、Token 登录、阿里云登录、H5 登录、悬浮球与运营心跳已接入 MainActivity；图标由出包资源覆盖模板占位图 | 心跳已做 JVM 业务模拟和本地 HTTP 验证；真实服务端、生产签名 APK 和多系统设备仍需验收 |
| Windows 出包 GUI | Tkinter 参数表单、路径选择、配置保存、后台构建服务已实现 | Python 3.8 单测；需在 Windows 桌面完成 GUI 手工验收和真实签名 APK 构建 |
| 渠道分包/读取 | 沿用 `tools/channel_apk.py`；Android 端 `ChannelCodeReader` 已实现 | 合成 Signing Block 测试通过；读取器尚未接入应用主流程，仍需真实签名 APK 的 apksigner 端到端验证 |

此文档记录当前工程实现与验收缺口；业务定义仍以需求文档及 JSBridge 方法清单为准，不将模拟测试当成设备验收。

## 2. Android 工程构建

安装 JDK 17、Android SDK Platform 36 和 Build Tools。Windows PowerShell 设置 SDK 路径后运行：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
```

Debug APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。正式多游戏出包请使用 GUI，不要直接修改模板里的包名、游戏 ID 或签名信息。

## 3. Windows GUI 母包出包

使用 Python 3.8 或更新版本，且已安装 JDK 17、Android SDK Platform 36。可以双击项目根目录的 `启动出包工具_start_packager.bat` 启动；也可以执行：

```powershell
py -3 -m packager.gui
```

GUI 表单要求填写游戏 ID、版本、桌面名称、Android 包名、签名 alias 和签名密码，并选择 keystore、PNG 图标、闪图目录和输出目录；游戏全屏模式必选横屏或竖屏，默认横屏，构建时写入 APK Activity 的固定屏幕方向。点击保存/加载配置时分别选择独立 JSON 文件，默认保存文件名为配置名称；签名密码不会保存，加载后须重新填写。Gradle 在临时工程副本中运行；构建参数与签名信息通过临时文件传入，并在构建结束后删除。

构建完成后，输出目录同时生成 `<Android包名>_<游戏版本>.apk` 和同名 `.txt`。信息文档使用本次配置的游戏名称、ID、版本和包名；“包签名”字段是**实际 APK 签名证书的 MD5 指纹**（32 位小写十六进制，不含冒号），供阿里云号码认证方案配置使用。出包工具使用 Android SDK Build Tools 中的 `apksigner verify --print-certs` 验证签名并读取指纹；验证失败、缺少工具或未取得唯一有效指纹时返回失败，不覆盖已有 APK 和信息文档。生成时间采用运行 GUI 电脑的本地时间。APK 与文档中的版本都取 GUI 的游戏版本。

### 在其他 Windows 电脑部署

- 将整个项目目录复制或克隆到新电脑，保留 `android`、`packager` 及 Gradle Wrapper 文件；不要只复制 GUI 脚本。
- 安装 JDK 17、Python 3.8+、Android SDK Platform 36 和 Android SDK Build-Tools；将 SDK 安装在默认 `%LOCALAPPDATA%\Android\Sdk`，或提前设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`。
- 双击 `启动出包工具_start_packager.bat`。脚本从自身所在目录启动 GUI，并自动尝试探测默认 Android SDK；需联网下载的 Gradle 依赖会在首次构建时获取。
- 更换电脑后，在 GUI 中重新选择该电脑可访问的签名证书、PNG 图标、闪图目录和输出目录；必要时填写新证书 Alias/密码。保存的 JSON 配置可拷贝使用，但其中的文件路径必须在新电脑存在或重新选择。签名证书和密码应安全单独传递，不要放入仓库。

### 闪图资源命名

当前映射器读取目录内 PNG 文件名中的尺寸标记，例如 `横板-1280x768.png`、`竖版-750x1334.png`，按宽高比复制到临时 Android 工程 `drawable-nodpi` 资源，资源名形如 `splash_landscape_1280x768.png`。非 PNG、文件名无尺寸、方形图会在构建前报错。样例资源目录见 `refs/打包资料/证书/闪图`。

映射器同时生成 `qsm_splash_images` 资源数组，Activity 启动时读取该数组：优先选择与屏幕横竖方向一致、宽高比最接近的图片；比例相同时优先高分辨率。缺少对应方向时回退到目录内其他方向的图片；空目录不展示本地闪图。重复尺寸只登记一次。

本地闪图使用等比例 `centerCrop` 全屏展示，首个 H5 页面可见后退出，最短展示约 1.2 秒、最长约 5 秒，初始化失败时立即退出以显示错误。初始化接口返回的 `bgImage` 是后续 H5 页面的底层背景，与本地闪图独立。启动窗口使用黑底；Android 12 及以上的系统启动阶段隐藏默认图标，随后衔接应用内闪图。修改后须重新出包并安装，已安装的旧 APK 不会自动应用模板修复。

初始化接口返回 `code!=0` 时，立即隐藏闪图，在原生页面显示“游戏初始化失败：”加服务端 `msg`，停止后续启动流程；失败响应无需包含 `data`。`msg` 缺失或空白时显示错误码作为回退提示。

### H5 Bridge 注入与就绪

初始化 `h5Pages` 中的隐私页（`agreement`）、登录页（`login`）和游戏页（`game`）均通过同一容器注册 `QSMNative`，对配置中可信主机的顶层页面注入 `QSMH5APP`。支持 `DOCUMENT_START_SCRIPT` 的 WebView 在页面脚本执行前完成注入；页面刷新和 `changeGameUrl` 后继续生效。旧 WebView 通过页面开始和加载完成回调补注入，不能保证首段同步脚本即可调用。

H5 初始化时先检查 `window.QSMH5APP`，未就绪则监听 `window` 的 `QSMH5APPReady` 事件后再调用；现有等待 Bridge 的轮询方式也可继续使用。注入脚本幂等，不会因补注入清空待返回的回调。原生方法分发到 UI 线程执行，避免 H5 打开/关闭窗口时跨线程操作 WebView。

设备回归测试：在 `android` 下执行 `./gradlew.bat connectedDebugAndroidTest`，覆盖隐私同意、登录通知、游戏 Token 读取、刷新与切换地址、打开配置窗口，以及非配置页面不注入。测试 Activity 和本地 HTTP 测试权限只进入 Debug APK。

线上页面只读联调可显式执行：

```powershell
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.qishimeng.game.bridge.BridgeInjectionTest#configuredLivePagesCanCallBridge' '-Pandroid.testInstrumentationRunnerArguments.bridgeLivePages=true'
```

该联调从游戏 ID 92 的初始化响应读取页面地址，逐页检查 `QSMH5APP.getGameInfo` 回调；不执行线上登录或隐私同意。2026-10-21 在 Android 15 WebView 中验证：线上隐私页、登录页、游戏页均注入成功且 `getGameInfo` 回调正常；隐私页和登录页的 `Bridge.bridgeOver` 均已就绪。

仍有 H5 调用迁移问题：登录页 `Base.sdkGetPreferenceData`、`Base.sdkLoginSuccess` 的部分路径调用旧 `QsmSDK`，游戏页 `Base.getSdkBaseData` 仍读取旧 `QsmSDK.getGameConfigInfo`。线上三个页面中的 `window.QsmSDK` 均为 `undefined`。这部分 H5 业务调用需对齐新接口，单纯完成 `QSMH5APP` 注入不能证明这些旧调用可用。

### 游戏登录后的悬浮球显示

2026-10-22 排查发现，初始化解析器已读取 `floatball`，但原有 `MainActivity` 没有创建 `FloatingBallView` 或连接 `FloatingBallController`；Bridge `setFloatingBall` 仍是固定返回 `false` 的占位回调，因此即使配置 `show=true` 且游戏加载成功也不会显示。

现已将悬浮球接入主流程：登录成功且 `web_game` 文档可见提交后，按初始化的显隐、图标、尺寸和位置创建。悬浮球位于独立原生层，保持在游戏 WebView 上方。H5 `setFloatingBall` 更新真实运行状态；点击悬浮球打开福利大厅，福利大厅打开时隐藏、关闭后恢复最新状态；游戏关闭或退出登录时隐藏，Activity 销毁时释放图片线程和视图。

`MainActivityFloatingBallTest` 运行真实 MainActivity，仅替换外部初始化/Token HTTP 传输，页面和图标使用本地真实 HTTP 服务。覆盖 H5 登录、Token 登录、游戏文档提交前不创建、初始隐藏、提前设置运行状态、激活图标、福利大厅开关、退出和关闭游戏；截图中心像素断言用于确认图标实际绘制在游戏页面上方。该设备回归不代表线上图片资源及完整设备矩阵已验收。

首次接入修复前 3 项专项均复现失败，接入后均通过。

同日对用户当前安装的正式包继续排查，确认悬浮球已在游戏上层创建，但横屏 `1280×720` 下，`width=36、height=36、right=10、bottom=200` 经宽度缩放后，实际球框为 `[1117,-119,1245,9]`，仅露出 9 像素。此前夹具使用较小的 `bottom=20`，未覆盖该真实配置问题。布局现优先采用悬浮层实测宽高，将尺寸与位置限制在可见区域内；窗口尺寸变化时重新计算，拖动上界也保证非负。

新增横屏大间距、窗口缩小和超大尺寸三个设备回归，修复前三项均能复现越界、修复后均通过。截图断言等待实际屏幕合成，避免启动闪图尚未退出或图标属性已更新但帧尚未绘制时误判。最新全量验证为 64 项单元测试通过、37 项设备测试中 33 项通过及 4 项按配置跳过，Debug 构建通过。独立只读代码审查未发现本次越界修复的待处理问题。

已使用天尊传奇现有出包配置重新生成正式签名 APK，通过签名验证并覆盖安装至 `emulator-5554`。用户完成登录后，现场截图确认右侧“福利”悬浮球完整可见；当时线上配置下球框为 `[1160,500][1245,585]`，位于 `1280×720` 可见区域内，所属独立原生层的绘制顺序为 2，在游戏 WebView 之上。

其他已安装的 APK 仍须重新出包并安装，旧包不会自动应用代码变更。心跳主流程接入已于 2026-10-29 补齐，见下节；此前正式包仍不具备这次补齐的逻辑。

### 运营心跳与弹窗管理（2026-10-29）

- 登录完成、游戏页 `web_game` 可见且悬浮球显示后，等待 `popupInterval.interval` 秒首次 POST `popupInterval.url`，请求体 `{}`，携带现有六项请求头和当次 Token。每次请求完成后等待配置间隔再发下一次，不并发叠加。
- `code != 0` 忽略，`code = 0` 同步 `data.floating_ball.show/active` 与 `data.popup_pages`。同状态不重新加载球图标；隐藏球不停止已有轮询，后续响应可恢复。
- 只清理已标记的运营弹窗。所有初始化 `h5Pages` 实例及其他功能窗口受保护；已存在 instance 不重载、不换地址、不前置。新运营窗口按清单顺序创建并前置。
- 新运营页 Bridge 使用原生来源和主 frame 校验，拒绝子 frame、其他主机和已销毁实例的请求。不支持 `WEB_MESSAGE_LISTENER` 的旧 WebView 只展示页面、不授予不安全的原生入口，并输出 `QSMHeartbeat` 警告；应更新 Android System WebView。功能页现有 Bridge 兼容路径不在本轮全面重构范围。
- 退出登录、关闭 `web_game` 停止轮询，重新显示球后重新计时；新会话恢复初始化球状态。宿主销毁释放线程，旧请求及主线程排队响应失效。
- 模拟测试覆盖实际本地 HTTP 请求、业务成功/非零码、隐藏与恢复、弹窗清单、重复实例、退出重启及过期回调。未进行此次真机打包或设备端 Bridge 注入时序测试，用户自行出包验收。

### 游戏角色按游戏索引及加载后清理

2026-10-22 调整角色 Bridge：`getGameRole({gameId:266})` 只需游戏 ID，无法读取时 `data:false`；`saveGameRole` 保持原参数，以 `gameId` 为唯一索引全量替换角色，未传可选字段不保留旧值，其他游戏资料保留。`web_game` 每次主文档成功提交后清空所有游戏角色，包括首次加载、刷新和 `changeGameUrl`；加载中、失败及其他页面加载不清理，也不影响通用本地存储。

新增真实 MainActivity 设备测试覆盖上述 Bridge 契约、持久化、加载等待、切换地址成功/404 失败、登录/福利页隔离，以及通用存储保留。崩溃前 3 项角色专项设备测试全部通过，独立只读审查未发现待修问题。系统崩溃后隔离损坏的 Gradle 转换缓存并强制重新构建，66 项单元测试通过、Debug APK 构建成功；完整设备回归因模拟器调试连接离线未能重新执行。本轮角色变更尚未重新生成或安装正式签名包，现有正式包不会自动应用源码变更。

### H5 页面加载容错与错误码

隐私页、登录页、游戏页和共用容器打开的其他 H5 窗口，加载主文档与自动重试期间保持静默，不显示加载文案、进度或重连提示。连接失败、代理错误、DNS 错误、超时及临时 HTTP 错误在首次失败后最多自动重试三次（含初次共四次），每次等待主文档可见提交最多五秒，失败后不额外延迟重试；成功后立即显示 H5。空白遮罩屏蔽 WebView 系统错误文档，仅最终失败显示原生错误码及“重试”“网络设置”按钮。从设置返回后重新加载；手动重试重新计算尝试次数，并立即隐藏上次错误提示。

| 错误码 | 含义与处理 |
| --- | --- |
| `H5_PROXY` | 代理连接失败，检查模拟器/WLAN 代理配置或更换网络 |
| `H5_DNS` | 域名解析失败，检查网络或 DNS |
| `H5_CONNECT` | 服务器连接或数据传输失败，检查网络及应用联网设置 |
| `H5_TIMEOUT` | 主文档在五秒内未能可见提交 |
| `H5_SSL` | 证书或安全连接校验失败，检查设备时间或网络；取消请求且不自动重试，不绕过证书验证 |
| `H5_HTTP_状态码` | 主文档 HTTP 失败；408、429、5xx 有限重试，其他 4xx 直接提示 |
| `H5_NETWORK` | 其他 WebView 主文档连接错误 |

仅主文档失败会替换整页；图片、脚本等子资源的失败以及 H5 自己的接口错误不触发原生整页重试。此处理不代表检查 H5 业务初始化是否完成。刷新、切换游戏地址时重新管理加载状态；窗口关闭后立即停止等待和重试。提示不展示完整 URL、账号或设备码。

自动重试只重放明确为 GET 的页面请求；页面内 POST 表单等非 GET 主文档失败后直接提示，不自动重复提交或转换为 GET。手动恢复使用最近一个明确的 GET 页面地址，便于重新操作表单。重定向的最终主文档失败同样进入错误码提示。

设备回归使用本地真实 HTTP 服务与拒绝连接端口，覆盖初次加载和三次自动重试期间无提示、503 四次失败后提示及手动恢复、404、图片失败、真实连接失败、五秒超时后恢复、关闭窗口取消重试、302 重定向成功/失败，以及 POST 失败后不自动改为 GET。执行 `./gradlew.bat connectedDebugAndroidTest`；代理错误分类另由单元测试覆盖，故障夜神环境仍需安装新包复验。

### 本地 Token 自动登录与清理

隐私同意且本地存在非空 `accountToken` 时，使用 POST `/h5_app/login_by_token`，请求 Header `Accountoken` 携带该 Token。

- `code=0` 且 `data.token` 是非空字符串：覆盖本地加密存储，完成登录并打开游戏页。
- 任意 `code!=0`：删除本地 Token，完成持久化后回退后续登录方式；不再依赖服务端失效码列表。即使失败响应含 `data.token`，也不保存。
- 未收到有效业务响应的网络异常：保留旧 Token并回退；`code=0` 但缺少有效新 Token 时也回退，不视为成功。

原生 `gameInfo()`、Bridge `getGameInfo` / `getAccountToken` 与后续原生请求都从同一存储读取最新 Token。删除后返回空字符串，请求 Header `Accountoken` 为空；已创建的一键登录服务在兑换时同样重新读取，避免旧快照残留。重新启动且期间没有其他方式登录成功时，跳过 Token 登录，继续阿里云或 H5 登录。

设备回归覆盖真实加密存储、WebView Bridge、实际 HTTP Header 和成功刷新。跨进程专项使用 `AccountTokenRestartTest`，依次传入 `tokenRestartPhase=prepare`、结束目标进程、再传 `tokenRestartPhase=verify`；校验两阶段 PID 不同且重启后不再请求 Token 登录。该专项默认跳过，运行时使用独立测试包。

### 阿里云一键登录顺序与接入检查

当前顺序为：隐私流程完成 → 有本地 Token 时尝试自动登录 → 初始化 `aliyunOnekeyConfig.open=1` 时尝试阿里云一键登录 → 取消或失败后回退 H5。本地 Token 已登录成功时不再展示一键登录；本地 Token 缺失或失败时，阿里云排在 H5 前面。

Android SDK 使用 `setAuthSDKInfo(scheme_secret)` 设置完整方案密钥，再调用 `getLoginToken(activity, timeout)` 唤起授权页。H5 `requestPhoneLogin` 无需传入 SDK 密钥等认证配置。

#### 2026-10-28 一键登录交互与 Bridge 唤起边界调整

- 正常弹窗的登录按钮字号由 18dp 调整为 16dp，较矮弹窗对应缩小；头部沿用此前增加 15% 后的高度。
- 点击复选框或“我已阅读并同意”等普通说明文字，均切换勾选状态；协议链接仍只打开详情，不改变勾选。
- `requestPhoneLogin` 只负责展示授权弹窗。SDK 返回 `600001` 后立即回复 `{code:0,msg:"唤起成功",data:true}`，JS 回调执行完成后关闭 `web_login`。展示前失败返回 `3001`，关闭功能返回 `3003`。
- 展示后的取消、切换方式及认证/兑换结果不再回调原请求；取消或失败创建新的 H5 登录窗口，兑换成功保存业务 Token 并进入游戏。SDK 单例由进程级会话保护，重复唤起不得覆盖既有监听器。
- 独立需求及 iOS 参考以 [阿里云一键登录独立需求](阿里云一键登录独立需求_aliyun-onekey-login.md) 为准。源码调整后须重新生成母包、渠道包，在手机安装后验证真实运营商取号及兑换。

本轮最终验证：80 项单元测试通过；66 项设备用例中 62 项通过、4 项按配置跳过；`assembleRelease`（含关键 Lint）通过。设备测试含真实 WebView 回调先送达后关闭、展示前失败、取消先于回调交付、后续认证成功不重复回调，以及真实 SDK Activity 中触摸说明文字与协议链接。SDK 页面使用离线假号码，未执行手机运营商取号及真实业务兑换。日志：`android/build/onekey-ui-verification/phone-launch-final-recheck.log`。

中间一轮全量曾出现 `MainActivityGameRoleTest.successfulGameLoadsClearAllGamesIncludingReloadAndChangeGameUrl` 的 JS 执行超时，以及 `UpdateDialogTest.multilineTitleIsFullyMeasuredAndRemainsScrollable` 的长标题裁切断言失败；期间更新弹窗源码有其它变更。按当前源码重新完整构建和回归后，两项均通过，最终报告无失败。

#### 2026-10-28 手机授权弹窗样式修复

授权页现使用居中弹窗，宽高分别按 `ui.dialog_width_percent` 和 `ui.dialog_height_percent` 计算；背景为 `#eeeeee`、14dp 圆角，窗口遮罩为黑色 45%。品牌头部采用 `#c52d28` 和浅弧下沿，展示 `box_logo.png`，关闭图标位于红色主体右侧。忽略 `ui.title`；橙色登录按钮使用 `ui.login_text`、白字；协议详情页标题栏为 `#c52d28`。

阿里云 2.14.24 隐藏导航后，内容区仍保留顶部 50dp；手机号、运营商说明、登录按钮与切换方式的偏移已据此换算。协议从底部定位，保留多行空间；较矮弹窗使用紧凑字号与间距。SDK 的自行取消模式通过 UI 事件处理返回键和切换登录，终态清理 Token/UI 监听器及自定义头部，避免重复回调和持有旧 Activity。

`OnekeyAuthUiTest` 直接在 SDK 真实 `LoginAuthActivity` 中注入离线假号码，验证默认高度及 50% 高度下内容完整显示、协议导航、关闭和系统返回键。该用例不调用取 Token 或业务登录；截图仅证明授权页布局，不代替手机 SIM 卡和蜂窝网络认证验收。源码修复后必须重新生成母包及渠道包，已有 APK 不会自动更新。

最终验证：68 项单元测试通过，50 项设备测试中 46 项通过、4 项按配置跳过，Release 编译及关键 Lint 检查成功。本轮只编译未签名模板 APK，未重新生成正式签名母包或渠道包。

#### 2026-10-27 初始化配置核对

使用 `Gameid: 92`、`Gameversion: 1.0.1`，其余设备与账号 Header 为空，POST 初始化接口返回 `code=0`。线上联调测试与天尊传奇保存的出包配置已使用该 ID 和版本；通用工程仍由出包参数设置游戏 ID/版本。历史 `1.0.0` APK 的身份信息不会自动更新。

| 接口字段 | 当前返回值 | 客户端处理 |
| --- | --- | --- |
| `open` | `1` | 开启一键登录 |
| `scheme_secret` | 已配置（不记录原文） | 传入 `setAuthSDKInfo` |
| `exchange_url` | `https://box.74meng.com/h5_app/login_by_onekey` | POST `auth_token` 兑换业务登录 Token |
| `screen_code` | `1` | 横屏授权弹窗 |
| `launch_total_timeout_ms` | `5000` | 传入 `getLoginToken` |
| `launch_attempt_timeout_ms` | `2000` | 尚未解析和实现 |
| `launch_max_attempts` | `2` | 尚未解析和实现 |
| `launch_retry_interval_ms` | `500` | 尚未解析和实现 |
| `app_protocols` | 用户协议、隐私政策各一条 | 分别设置 SDK 两条自定义协议，保留返回的名称和 URL；当前 `sid=9&gid=92`，`type` 分别为 `1`、`2` |
| `ui.title` | 手机快捷登录 | SDK 导航标题 |
| `ui.login_text` | 手机号一键登录 | SDK 登录按钮文案 |
| `ui.other_login_text` | 使用其他方式登录 | SDK 切换登录方式文案 |
| `ui.primary_color` | `#CC0000` | SDK 登录按钮文字颜色 |
| `ui.dialog_width_percent` | `60` | 按横屏可用视口计算宽度 |
| `ui.dialog_height_percent` | `75` | 按横屏可用视口计算高度 |

本次核对覆盖接口返回、配置解析和 SDK 参数传递，不代表真机授权弹窗及凭证兑换已验收。客户端当前未实现上述三个重试相关参数，不能声称接口的一键登录设置全部生效。

SDK 必须同时打入随包 `SDK/` 目录中的 `auth_number_product-2.14.24-log-online-standard-cuum-release.aar`、`main-2.2.3-release.aar` 和 `logger-2.2.2-release.aar`。缺少后两者会因运行时类不可用导致 helper 初始化失败，编译通过不能证明依赖完整。按随包 Demo 显式注册 `LoginAuthActivity`、`PrivacyDialogActivity` 和 `AuthWebVeiwActivity`；内部 Activity 不导出，前两者使用 SDK 弹窗主题与 `screenOrientation=behind`。

2026-10-21 修复上述依赖和 Activity 缺失：设备回归已验证依赖类可加载及三个 Activity 已注册。使用游戏 ID 92 线上初始化配置在本机 Debug 包中执行 SDK 环境检查，SDK 可初始化并返回 `600017`（AppID Secret 解析失败），不代表正式包的一键登录已验收。需确认初始化 `scheme_secret` 是阿里云 Android 号码认证方案提供的完整 SDK 密钥（不是 AccessKey Secret），并核对正式包与方案绑定的包名、签名。模拟器不能替代有真实 SIM 和运营商网络的手机验收。

只读线上环境检查需显式运行以下命令；仅初始化与检查，不拉起授权页、不取得手机号或 Token、不调用登录兑换，输出只保留阶段与 SDK 结果码：

```powershell
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.qishimeng.game.auth.AliyunEnvironmentTest' '-Pandroid.testInstrumentationRunnerArguments.onekeyLiveEnv=true'
```

默认设备测试仅执行依赖和 Activity 注册检查，线上环境检查默认跳过。

### 启动联网提示与设备码

启动前检查是否有具备互联网能力的网络连接。无连接时提示开启 WLAN 或移动数据，提供“网络设置”“重试”“退出”；存在连接但初始化请求因断网、超时或系统联网限制失败时，提示检查网络和本应用联网设置，提供“应用联网设置”“重试”“退出”。从设置返回后重新检查并继续初始化，同一次初始化不会并发重复发送请求。

清单已声明 `INTERNET` 和 `ACCESS_NETWORK_STATE`。Android 将这两项作为普通权限授予，不存在通用的运行时“允许联网”授权框；厂商系统的应用联网限制须由用户在系统设置中开启，客户端不能代替用户自动放行。网络连接状态也不能直接判定某个应用是否被厂商限制，最终以请求结果为准。

设备码链路为：隐私同意 → OAID（当前未接入厂商 OAID 实现，默认不可用）→ `ANDROID_ID` → 随机 DIY 回退。OAID/`ANDROID_ID` 读取失败不会阻断回退；前两者 MD5 大写，DIY MD5 小写，均为 32 位。首次取得后加密保存并在后续启动复用，已有 DIY 码不会被新出现的系统标识覆盖。

`agreePrivacyPolicy` 成功回调前完成设备码生成和保存；同意后 `getGameInfo.data.deviceCode` 与原生请求 Header `Devicecode` 使用同一个值。未同意或撤回同意时，H5 返回值与请求 Header 均为空，不读取或生成设备码。H5 如缓存了同意前的 `getGameInfo` 结果，应在同意成功后重新获取。

设备回归覆盖：离线提示、设置返回与重试、连接存在但请求失败的设置入口；真实 WebView 同意前后回调、加密保存及重建提供器后的复用、HTTP 请求 Header、撤回后的空值。

### H5 页面实例生命周期

- 登录成功打开 `web_game` 后销毁 `web_login`，各登录方式共用同一切换入口。页面关闭沿用约 200ms 淡出，结束后移除视图并调用 `WebView.destroy()`。
- 页面从容器关闭时，其 Bridge 立即失效；派发请求前同时校验实例仍存在且 WebView 对象身份相同，拒绝旧文档在淡出期间或排队后操作新会话。
- `logout` 清除持久化登录凭证，销毁游戏、福利大厅和运营弹窗等当前会话页面并隐藏悬浮球，然后按初始化配置从一键登录步骤重新开始：开启则尝试阿里云授权，未开启、配置不足、取消或认证/兑换失败则创建新的 H5 登录页；不复用旧登录文档，不再用旧 Token 自动登录，也不重跑初始化/更新检查。重新登录成功保存新 Token 并创建新的游戏 WebView；通用本地存储与隐私同意状态保留。
- 打开福利大厅只创建或置顶 `web_welfare`，保留游戏实例与当前文档。大厅调用 `changeGameUrl` 在原游戏 WebView 中加载新地址并置顶，保留大厅在后方；此时恢复运行时悬浮球状态。导航会替换游戏文档，但不销毁或重建 WebView。
- `MainActivityFloatingBallTest` 覆盖登录页移除、退出后登录与游戏实例更新、大厅反复打开时实例复用、换地址后的游戏对象身份及悬浮球恢复；使用真实 MainActivity、WebView 与本地 HTTP 页面。
- 2026-10-23 验证：新增两项生命周期回归在修复前均失败；旧游戏退出回调继续打开游戏、覆盖 Token 的回归也在修复前失败。修复后 66 项单元测试通过，Android 15 模拟器全量设备测试 43 项中 39 项通过、4 项按配置跳过（两个跨进程专项及两项线上环境专项），无失败，Debug 构建通过。独立只读审查发现的旧 Bridge 时序问题已修正并复核通过。本次没有重新生成或覆盖安装天尊传奇正式包。

### 原生版本更新验证（2026-10-28）

- 在 `android` 下执行 `./gradlew.bat testDebugUnitTest assembleDebug assembleRelease`，80 项单元测试通过，Debug 和 Release 构建成功。Release 为模板未签名包，不替代 GUI 生产签名出包。
- Android 15 模拟器更新专项 12 项全部通过：窗口宽度/固定高度/自适应高度、空文案、长描述和多行标题、45% 遮罩、强制返回拦截、进度与安装按钮、安装 URI 可读、授权返回/拒绝、隐私先于更新、关闭后登录、关闭更新配置，以及真实本地 HTTP APK 下载后自动与再次请求安装。独立测试包名为 `com.qishimeng.updateverification`。
- 同时核对当前源码的全量 `connectedDebugAndroidTest` 报告：66 项中 62 项通过、4 项按配置跳过，0 失败、0 错误；12 项更新专项全部包含在全量报告内。跳过项为现有跨进程与线上环境专项。
- 独立代码审查发现的长标题裁切已先用 9 行标题复现，再修复并通过设备回归。头部在长标题时扩展同色背景，上传 PNG 始终按原始比例保留；资源 SHA-256 与用户原图一致。
- 已保存并逐张核对界面截图：[更新提示](images/update-offer.png)、[下载进度](images/update-progress.png)、[下载完成与立即安装](images/update-ready.png)。截图底层为黑色测试 Activity；遮罩透明度另由窗口属性断言验证。
- 安装测试验证 FileProvider 文件可读及实际安装 Intent 的发出，拦截最终安装调用，未自动点击系统确认；Android 8+ 安装授权分支通过注入授权结果验证。尚需用生产签名渠道包在 Android 8/10/13/15 真机上验证设置页返回、安装确认及覆盖升级；外部浏览器分支已有状态机测试，仍需真实浏览器联调。
- 使用本次功能须先通过 GUI 重新构建母包，再运行渠道分包脚本。仅重新分包旧母包不会更新原生代码。`url` 应由服务端指向相应渠道 APK，确保更新后渠道保持一致。

## 4. 渠道分包与验签

在 GUI 产出的签名 APK 上运行：

```powershell
py -3 tools/channel_apk.py .\base.apk .\channel.apk "1-25-6#10028"
```

母包和渠道包都应运行 Android SDK Build Tools 中的 `apksigner verify --verbose --print-certs`，确认签名有效且证书指纹一致。`ChannelCodeReader` 可从 Signing Block 读取完整 UTF-8 渠道码，异常返回空字符串；仍需将其接入 `GameInfo`、`getGameInfo.data.packageCode` 和服务器请求 Header `Packagecode` 后进行设备端验收。

## 5. Android/H5 联调清单

- 初始化：确认 `POST https://box.74meng.com/h5_app/init` 的请求体、六项 Header 和真实响应字段/错误处理。
- 登录：核对 Token 登录成功覆盖、任意非 0 清理和重启跳过；核对阿里云 `exchange_url` 的请求体字段、回包 token 和本机安全存储。
- 隐私：首次同意、拒绝、撤回后重启；未同意前确认 `Devicecode`、`Accountoken` 均为空。
- WebView/Bridge：在实际 WebView 验证 `QSMH5APP` 调用形式、回调时机、页面销毁竞态、隐私同意/拒绝、登录通知、角色读写和 URL 切换。
- 心跳/窗口：验证服务端返回结构、间隔单位、多弹窗和实例清理、`web_game`/`web_welfare` 与悬浮球状态协调。
- 更新：覆盖 `open=0`、正常强制/非强制更新、百分比尺寸、自适应高度、外部浏览器/内部下载两种模式、未知来源安装授权、取消安装后再次安装，以及字段错误、网络失败和下载失败；异常必须放行登录。
- 出包：至少用两份游戏配置分别构建，检查包名、桌面名、游戏常量、图标、闪图选择、签名证书和 APK 安装。
- 设备矩阵：Android 8、10、13、15 的 WebView、窗口/屏幕方向、系统安装器和返回键行为均需记录设备型号、API 与 WebView 版本。

以上服务端契约和设备项目未完成实际联调前，均标记为待验收，不以单元测试替代。
