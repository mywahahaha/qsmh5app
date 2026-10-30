# 项目文档导航

本文面向 Android 与 H5 联调，说明项目文档的用途、优先级和当前状态。SDK 初始化接口硬编码为 `https://box.74meng.com/h5_app/init`，配置结构和联调待确认项以需求文档为准。

## 文档关系

| 文档 | 用途 | 当前状态 |
| --- | --- | --- |
| [安卓 H5 游戏包开发需求文档](安卓H5游戏包开发需求文档_requirements.md) | 从零开发 Android 游戏工程、模块职责、出包工具与验收目标；业务口径以此为准 | 开发基线；Android 主流程接线及设备验收仍在进行 |
| [初始化接口字段说明](docs/初始化接口字段说明_initialization-api.md) | 初始化请求、响应及 `updateInfo` 最新字段、迁移关系 | 2026-10-28 更新 `open/width/height/popup/force/title/time/desc/version/url`；原生更新已接线，验证结果见联调指南 |
| [阿里云一键登录独立需求](docs/阿里云一键登录独立需求_aliyun-onekey-login.md) | Android/iOS 共用的一键登录流程、配置、兑换接口、弹窗视觉、Bridge、异常回退及验收 | 含标题栏增高 15% 的最新要求、Logo 与参考图；明确 iOS 接入待确认项 |
| [JSBridge 方法清单](JSBridge方法清单_jsbridge.md) | H5 与 Android 的方法名、参数、回调和返回码 | 业务规则已对齐；方法签名仍需联调确认，含 `changeGameUrl` |
| [安卓渠道分包设计文档](安卓渠道分包设计文档_android_channel_packaging.md) | 已签名 APK 的渠道码写入、分包脚本使用及验收 | 脚本、原生读取器与合成 APK 回归测试已实现；读取器主流程接线和真实 APK 验签待验收 |
| [安卓工程构建与联调指南](docs/安卓工程构建与联调指南_android-build-and-integration.md) | Android Debug 构建、Windows GUI 母包出包、渠道分包和联调验收步骤 | 工具代码已实现；桌面 GUI、签名出包、服务端及真机联调待验收 |
| [Vue2 JSBridge 示例](示例/vue2-jsbridge-demo.html) | Vue2 CDN 页面调用基础信息、窗口关闭、`isAppInstalled` 和 `openApp` 的参考示例 | 含第三方应用包名输入和按钮，需在已注入新版 `QSMH5APP` 的 SDK WebView 中运行 |
| [阿里云号码认证 SDK 压缩包](refs/numberAuthSDK_APP_Android_v2.14.24_operator_ui_log_static.zip) 与[接入说明](refs/安卓一键登录集成文档.txt) | Android 原生一键登录 SDK 与官方接入文档入口 | SDK v2.14.24；由 Android 原生模块集成 |

## 联调时的口径

- 业务行为、流程和字段以需求文档为准；具体 Bridge 方法签名以 JSBridge 方法清单为准。未标记待确认的业务规则视为已定稿；Bridge 签名和服务端未提供的接口契约仍需联调确认。
- 一键登录的完整专项要求以[独立需求文档](docs/阿里云一键登录独立需求_aliyun-onekey-login.md)为准，Android 和 iOS 可直接据此开发；平台特有适配与未确认项按文内标注执行。
- 分包文档只展开渠道 APK 的实现和操作步骤。若与需求文档中的字段名、Bridge 方法或业务行为冲突，应先按需求文档修订分包文档；涉及 Bridge 方法签名时，同时核对 JSBridge 方法清单。
- 当前仓库中的渠道分包脚本、H5 示例和阿里云 SDK 资料是辅助参考；Android Gradle 主工程、原生模块和 GUI 工具已进入实现阶段，不能将模块单测等同于应用主流程已接通。服务器已部署，仍须进行真实服务端、WebView、JSBridge 和设备联调。

## 推荐联调顺序

1. 先确认需求文档第 12 节列出的接口、状态和失败处理事项。
2. Android 与 H5 对齐 JSBridge 命名空间、方法签名、回调时机和错误码。
3. 按主流程联调初始化、隐私协议、登录、游戏页、运营窗口及退出登录。
4. 使用分包文档制作渠道 APK，验签并检查 `packageCode` 的 Bridge 返回值和 `Packagecode` HTTP Header。
5. 按需求文档补齐异常场景验收，记录实际 SDK 版本、设备环境与结果。

## 文档维护

需求或协议有变更时，先更新需求文档，再同步更新 JSBridge 清单、分包说明和示例。定稿后更新文档版本、日期及状态，并在联调记录中注明 SDK 与 H5 版本。
