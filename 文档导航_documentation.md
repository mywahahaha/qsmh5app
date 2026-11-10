# 项目文档导航

本文面向 Android、H5 联调及 iOS 开发，说明项目文档的用途、优先级和当前状态。SDK 初始化接口硬编码为 `https://box.74meng.com/h5_app/init`，配置结构和联调待确认项以需求文档为准。

进度更新（2026-11-02）：用户已确认此前 Android 版本完成真机测试；后续悬浮球 dp 尺寸、垂直位置、GIF 动画、成功唤起安装器后退出游戏任务及 H5 首帧就绪后淡入的改动须单独设备复验。iOS 已完成首期工程、渠道读取修复和悬浮球及心跳状态同步；51 项核心测试、10 项模拟器 UI 测试、15 项 JavaScript 测试通过，构建 3 Ad Hoc IPA 已验签，详见[悬浮球记录](docs/iOS悬浮球需求与实现_ios-floating-ball.md)和[首期开发与联调记录](docs/iOS首期开发与联调记录_ios-bootstrap-development.md)。运营清单 `popup_pages` 尚未实现，真机安装由用户完成。Android 具体进度及历史验证记录见[安卓工程构建与联调指南](docs/安卓工程构建与联调指南_android-build-and-integration.md)；代码与文档审查发现的问题另行跟踪。

平台开发边界以根目录 [AGENTS.md](AGENTS.md) 为准：Windows 仅开发安卓端，macOS 仅开发 iOS 端，不跨平台修改另一端专属代码、资源和构建配置；共用文档可随任务同步。

2026-11-03 iOS 更新：构建 5 真机安装和图标已由用户确认；同意隐私后的闪退日志定位到阿里云授权页方向检查。构建 6 已修复联网等待和授权方向并完成签名，61 项核心、14 项模拟器 UI、2 项真实 SDK 方向专项通过，待用户覆盖安装复验，详见[修复记录](docs/iOS首次联网与授权闪退修复_ios-startup-auth-fix.md)。

## 文档关系

| 文档 | 用途 | 当前状态 |
| --- | --- | --- |
| [安卓 H5 游戏包开发需求文档](安卓H5游戏包开发需求文档_requirements.md) | 从零开发 Android 游戏工程、模块职责、出包工具与验收目标；业务口径以此为准 | Android 主流程已接线；此前版本真机测试已完成，最新悬浮球位置变更待设备复验 |
| [初始化接口字段说明](docs/初始化接口字段说明_initialization-api.md) | 初始化请求、响应、`updateInfo` 字段及两端悬浮球尺寸/位置换算 | top 优先；Android dp 并支持 GIF 动画、iOS pt 与 safeArea（当前 GIF 首帧）；Android 安装器唤起后退出任务，最新改动待设备复验 |
| [阿里云一键登录独立需求](docs/阿里云一键登录独立需求_aliyun-onekey-login.md) | Android/iOS 共用的一键登录流程、配置、兑换接口、弹窗视觉、Bridge、异常回退及验收 | Android 真机测试已完成（用户确认）；iOS 接入与验收仍单独跟踪 |
| [JSBridge 方法清单](JSBridge方法清单_jsbridge.md) | H5 与原生的方法名、参数、回调和返回码 | v0.17：两端兼容矩阵、公共调用、iOS packCode 规则及悬浮球共用调用；完整业务仍需联调 |
| [安卓渠道分包设计文档](安卓渠道分包设计文档_android_channel_packaging.md) | 已签名 APK 的渠道码写入、分包脚本使用及验收 | 脚本、原生读取器与合成 APK 回归测试已实现，读取器已接入主流程；Android 真机测试已完成，APK 验签结果单独记录 |
| [安卓工程构建与联调指南](docs/安卓工程构建与联调指南_android-build-and-integration.md) | Android Debug 构建、Windows GUI 母包出包、渠道分包和联调验收步骤 | 此前版本 Android 真机测试已完成；最新悬浮球变更的构建、单测与待设备复验情况单独记录 |
| [Vue2 JSBridge 示例](示例/vue2-jsbridge-demo.html) | Vue2 CDN 页面调用基础信息、窗口关闭、`isAppInstalled` 和 `openApp` 的参考示例 | 含第三方应用包名输入和按钮，需在已注入新版 `QSMH5APP` 的 SDK WebView 中运行 |
| [阿里云号码认证 SDK 压缩包](refs/numberAuthSDK_APP_Android_v2.14.24_operator_ui_log_static.zip) 与[接入说明](refs/安卓一键登录集成文档.txt) | Android 原生一键登录 SDK 与官方接入文档入口 | SDK v2.14.24；由 Android 原生模块集成 |
| [iOS开发总体方案](docs/iOS开发总体方案_ios-development-proposal.md) | 功能迁移、平台差异、架构与 Bridge、H5 载入及导航拦截、更新入口、隐私撤回及阶段估算 | v0.3 目标方案；已创建首期工程，范围和验证以开发记录为准；渠道 Header 默认空 |
| [iOS原生UI设计规范](docs/iOS原生UI设计规范_ios-native-ui-design.md) | 一键登录、协议、兑换、更新、错误、悬浮球、安全区域与两端视觉验收 | 设计草案；明确 SDK 最小尺寸和系统分发界面差异 |
| [iOS开发环境测试与签名出包手册](docs/iOS开发环境测试与签名出包手册_ios-environment-testing-release.md) | Mac/Xcode、真机与模拟器、Apple 账号、证书、签名、出包和发布门槛 | 完整目标手册；首期实际命令见开发指南，模拟器、归档与 Ad Hoc 签名已验证，真机安装未验收 |
| [iOS开发就绪检查](docs/iOS开发就绪检查_ios-readiness-check.md) | 本机工具链、临时编译与模拟器运行实测、签名材料匹配及文档缺口 | 开发前历史检查；当前已有工程和真实 SDK，最新结果见首期开发记录 |
| [iOS首期实施计划](docs/iOS首期实施计划_ios-bootstrap-plan.md) | 初始化、隐私、H5 登录、容器、Bridge 与替身测试的实施范围 | 首期实现、测试及 Ad Hoc 签名出包完成 |
| [iOS首期开发与联调记录](docs/iOS首期开发与联调记录_ios-bootstrap-development.md) | 本次身份、模块、测试证据、SDK、归档与未完成项 | 2026-11-02 最新事实记录；Ad Hoc IPA 已验签，仅限描述文件登记的 6 台设备 |
| [iOS开发指南](ios/开发指南_development.md) | 工程打开、本地运行、测试、SDK 安装、归档和签名命令 | 可执行指南；真机由用户自行安装验收，最新修复包为构建 6 |
| [iOS悬浮球需求与实现](docs/iOS悬浮球需求与实现_ios-floating-ball.md) | Android 规则逐项核对、iOS 状态/布局/拖动/心跳、H5 通用调用及验证 | 2026-11-04 修正 `right: 0` 屏幕贴边；游戏 92 构建 3 已签名，运营清单 `popup_pages` 尚未实现 |
| [iOS游戏背景闪图与打包器需求分析](docs/iOS游戏背景闪图与打包器需求分析_ios-assets-packager-feasibility.md) | Android 图片与打包器行为、iOS 缺口、Mac 多游戏签名出包方案及验收 | 开发前方案；已按顺序实施，当前状态见下方实施记录，旧构建 3 仍为 6 台 |
| [iOS图片与打包器实施记录](docs/iOS图片与打包器实施记录_ios-assets-packager-development.md) | 背景、闪图、首帧过渡、多游戏构建与 Mac GUI 实现及证据 | 构建 4：56 项核心、13 项 UI、15 项 JS、8 项后端测试通过；Ad Hoc 最终 IPA 验证通过，未做真机安装 |
| [iOS首次联网与授权闪退修复](docs/iOS首次联网与授权闪退修复_ios-startup-auth-fix.md) | 构建 5 真机反馈、联网等待、SDK 方向修复、回归及崩溃日志提取 | 真机崩溃栈确认阿里云授权展示方向异常；构建 6 已验签，61 项核心及 14 项 UI 通过，待覆盖安装复验 |
| [iOS授权品牌UI与全屏容器修复](docs/iOS授权品牌UI与全屏容器修复_ios-auth-ui-fullscreen.md) | 对照 Android 与设计文档实现 SDK 品牌授权弹窗、禁止 H5 容器拖动和真全屏 | 构建 7 已签名；69 项核心、15 项 UI、3 项真实 SDK 专项通过，真机视觉及取号待用户复验 |
| [iOS渠道读取与授权标题修复](docs/iOS渠道读取与授权标题修复_ios-channel-auth-header-fix.md) | 安装后直接读取自身渠道目录；标题增高、Logo 放大和垂直居中 | 构建 8 母包及 1-218 渠道包已签名；70 项核心和 3 项 SDK 专项通过，真机待复验 |
| [iOS14兼容实施记录](docs/iOS14兼容实施记录_ios14-compatibility.md) | iPhone 8 Plus iOS 14.4.2 安装兼容；修正系统 API 可用性并更新签名描述文件 | 构建 9 两包已签名；71 核心、15 UI、3 SDK 专项通过，真机安装及 SIM 取号待复验 |
| [iOS游戏打包器使用指南](ios/Packager/使用指南_usage.md) | 图形工具、配置、资源规范、签名材料、CLI 与产物说明 | 本机 Xcode 16.4 / Python 3.12 验证，工具与游戏安装包的分发签名独立 |
| [iOS原生界面预览](docs/iOS原生界面预览_ios-native-ui-preview.html) | 横竖屏授权、协议交互、兑换、普通/强制更新、隐私撤回及网络错误的本地可交互设计预览 | v0.2 设计演示，复用 Android 素材；不连接真实 SDK，不执行清理或安装 |

## 联调时的口径

- 业务行为、流程和字段以需求文档为准；具体 Bridge 方法签名以 JSBridge 方法清单为准。未标记待确认的业务规则视为已定稿；Bridge 签名和服务端未提供的接口契约仍需联调确认。
- 一键登录的共用业务要求以[独立需求文档](docs/阿里云一键登录独立需求_aliyun-onekey-login.md)为准；iOS 的原生设计、SDK 限制和安全区域适配参照新增 iOS 方案与 UI 规范草案，未确认项先完成决策，不将设计建议视为已有实现。
- 分包文档只展开渠道 APK 的实现和操作步骤。若与需求文档中的字段名、Bridge 方法或业务行为冲突，应先按需求文档修订分包文档；涉及 Bridge 方法签名时，同时核对 JSBridge 方法清单。
- 当前仓库中的渠道分包脚本、H5 示例和阿里云 SDK 资料是辅助参考；Android Gradle 主工程及原生模块已接入应用主流程，用户已确认完成 Android 真机测试。后续变更按下方顺序复验，并记录服务端、WebView、JSBridge 及设备环境。

## 推荐联调顺序

1. 先确认需求文档第 12 节列出的接口、状态和失败处理事项。
2. Android 与 H5 对齐 JSBridge 命名空间、方法签名、回调时机和错误码。
3. 按主流程联调初始化、隐私协议、登录、游戏页、运营窗口及退出登录。
4. 使用分包文档制作渠道 APK，验签并检查 `packageCode` 的 Bridge 返回值和 `Packagecode` HTTP Header。
5. 按需求文档补齐异常场景验收，记录实际 SDK 版本、设备环境与结果。

## 文档维护

iOS 后续开发先阅读首期开发记录与开发指南，再参考总体方案、D01–D11 决策表及 UI 规范。完整目标与首期实现分开标记；充值专项不纳入本轮。Android 真机测试完成状态不能沿用为 iOS 验收结论。

需求或协议有变更时，先更新需求文档，再同步更新 JSBridge 清单、分包说明和示例。定稿后更新文档版本、日期及状态，并在联调记录中注明 SDK 与 H5 版本。
