# 项目文档导航

本文面向 Android、H5 联调及 iOS 开发筹备，说明项目文档的用途、优先级和当前状态。SDK 初始化接口硬编码为 `https://box.74meng.com/h5_app/init`，配置结构和联调待确认项以需求文档为准。

进度更新（2026-11-02）：用户已确认此前 Android 版本完成真机测试；后续悬浮球 dp 尺寸、垂直位置、GIF 动画、成功唤起安装器后退出游戏任务及 H5 首帧就绪后淡入的改动须单独设备复验。具体进度及历史验证记录见[安卓工程构建与联调指南](docs/安卓工程构建与联调指南_android-build-and-integration.md)；代码与文档审查发现的问题另行跟踪。

平台开发边界以根目录 [AGENTS.md](AGENTS.md) 为准：Windows 仅开发安卓端，macOS 仅开发 iOS 端，不跨平台修改另一端专属代码、资源和构建配置；共用文档可随任务同步。

## 文档关系

| 文档 | 用途 | 当前状态 |
| --- | --- | --- |
| [安卓 H5 游戏包开发需求文档](安卓H5游戏包开发需求文档_requirements.md) | 从零开发 Android 游戏工程、模块职责、出包工具与验收目标；业务口径以此为准 | Android 主流程已接线；此前版本真机测试已完成，最新悬浮球位置变更待设备复验 |
| [初始化接口字段说明](docs/初始化接口字段说明_initialization-api.md) | 初始化请求、响应、`updateInfo` 字段及安卓悬浮球尺寸/位置换算、迁移关系 | 2026-11-02 悬浮球采用 dp/比例并支持 GIF；安装器成功唤起后退出游戏任务；最新变更待设备复验 |
| [阿里云一键登录独立需求](docs/阿里云一键登录独立需求_aliyun-onekey-login.md) | Android/iOS 共用的一键登录流程、配置、兑换接口、弹窗视觉、Bridge、异常回退及验收 | Android 真机测试已完成（用户确认）；iOS 接入与验收仍单独跟踪 |
| [JSBridge 方法清单](JSBridge方法清单_jsbridge.md) | H5 与 Android 的方法名、参数、回调和返回码 | 业务规则已对齐；方法签名仍需联调确认，含 `changeGameUrl` |
| [安卓渠道分包设计文档](安卓渠道分包设计文档_android_channel_packaging.md) | 已签名 APK 的渠道码写入、分包脚本使用及验收 | 脚本、原生读取器与合成 APK 回归测试已实现，读取器已接入主流程；Android 真机测试已完成，APK 验签结果单独记录 |
| [安卓工程构建与联调指南](docs/安卓工程构建与联调指南_android-build-and-integration.md) | Android Debug 构建、Windows GUI 母包出包、渠道分包和联调验收步骤 | 此前版本 Android 真机测试已完成；最新悬浮球变更的构建、单测与待设备复验情况单独记录 |
| [Vue2 JSBridge 示例](示例/vue2-jsbridge-demo.html) | Vue2 CDN 页面调用基础信息、窗口关闭、`isAppInstalled` 和 `openApp` 的参考示例 | 含第三方应用包名输入和按钮，需在已注入新版 `QSMH5APP` 的 SDK WebView 中运行 |
| [阿里云号码认证 SDK 压缩包](refs/numberAuthSDK_APP_Android_v2.14.24_operator_ui_log_static.zip) 与[接入说明](refs/安卓一键登录集成文档.txt) | Android 原生一键登录 SDK 与官方接入文档入口 | SDK v2.14.24；由 Android 原生模块集成 |
| [iOS开发总体方案](docs/iOS开发总体方案_ios-development-proposal.md) | 功能迁移、平台差异、架构与 Bridge、H5 载入及导航拦截、更新入口、隐私撤回及阶段估算 | v0.3：已确认国内/企业签名意向，本次排除分发模式及充值专项；保留隐私撤回；渠道 Header 默认空；尚未创建 iOS 工程 |
| [iOS原生UI设计规范](docs/iOS原生UI设计规范_ios-native-ui-design.md) | 一键登录、协议、兑换、更新、错误、悬浮球、安全区域与两端视觉验收 | 设计草案；明确 SDK 最小尺寸和系统分发界面差异 |
| [iOS开发环境测试与签名出包手册](docs/iOS开发环境测试与签名出包手册_ios-environment-testing-release.md) | Mac/Xcode、真机与模拟器、Apple 账号、证书、签名、出包和发布门槛 | 筹备手册；命令为未来工程约定，未执行 iOS 编译、签名或验收 |
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

iOS 开发前先阅读总体方案及 D01–D11 决策表，再按 UI 规范与环境手册完成 S0；H5 游戏载入与统一白名单拦截、隐私撤回、空 Packagecode Header 及本次范围按 v0.3 执行，充值专项不纳入；方案确认后编写实施计划。Android 真机测试完成状态不能沿用为 iOS 验收结论。

需求或协议有变更时，先更新需求文档，再同步更新 JSBridge 清单、分包说明和示例。定稿后更新文档版本、日期及状态，并在联调记录中注明 SDK 与 H5 版本。
