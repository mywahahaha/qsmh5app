# iOS 14 兼容实施记录

日期：2026-11-04。目标设备：iPhone 8 Plus、iOS 14.4.2。构建号：9，版本号：1.0.1。

## 原因与兼容范围

构建 8 的 `MinimumOSVersion` 为 16.0，所以 iOS 14.4.2 在安装阶段就会被拒绝。构建 8 IPA 内嵌的描述文件也早于本机 2026-11-04 更新的文件；两份设备清单有一台差异，后续产物必须使用更新后的 `游戏出包/iOS/证书/h5App.mobileprovision` 重新签名。

本轮将 App、单元测试、UI 测试的部署目标统一设为 iOS 14.0，同时更新公共 `.xcconfig`、现有 Xcode 工程和工程生成脚本，避免下一次生成工程又回到 iOS 16。保留构建 8 的渠道读取、游戏容器、闪图、背景图和阿里云品牌授权 UI。

降低目标后，编译器发现并修正：

| 原实现 | iOS 14 兼容实现 |
| --- | --- |
| `ContinuousClock.sleep(for:)`，iOS 16 起 | `Task.sleep(nanoseconds:)`；保持取消及完整心跳间隔语义 |
| `WKWebView.callAsyncJavaScript`，iOS 15 起 | 页面上下文中用 `evaluateJavaScript` 回调；参数先经 JSON 序列化，并转义 Unicode 行分隔符，保持文档 ID 与请求 ID 校验 |
| `WKWebView.underPageBackgroundColor`，iOS 15 起 | 仅在 iOS 15 及以上设置；iOS 14 保持 WebView 与 ScrollView 透明背景 |
| `URLSession.bytes(for:)`，iOS 15 起 | 统一用 `URLSessionDataDelegate` 分块接收；保持 16 MB 上限、HTTP 状态、URL 跳转/最终地址校验、无 Cookie、可取消和图片缩放解码 |

本机锁定的阿里云 2.14.19 三个静态 framework 的 arm64 对象声明最低系统为 iOS 8/9，低于本轮目标。阿里云[官方 iOS 接入文档](https://help.aliyun.com/zh/pnvs/developer-reference/the-ios-client-access)也描述了 iOS 12 起的 Network.framework 集成要求；这只是 SDK 二进制与构建可用性依据，真实 SIM 取号仍需 iOS 14.4.2 真机验收。

## 验证

- iOS 14.0 目标下编译后，核心测试 71 项通过：`ios/artifacts/test-run.5W6Kqs/Tests.xcresult`。新增 Bridge 回调序列化恶意文本回归，覆盖引号和 Unicode 行分隔符。现网比例加测 iPhone 8 Plus 横屏 736×414pt 的授权布局。
- 阿里云真实 SDK 配置专项 3 项通过：`ios/artifacts/iOS14兼容SDK专项_build9.xcresult`。
- 两份 Release 归档与 Ad Hoc IPA 严格验签通过。IPA 的 `MinimumOSVersion` 和 Mach-O `minos` 均为 14.0；Swift 并发回移运行库 `Frameworks/libswift_Concurrency.dylib` 已随包嵌入并签名。两包内嵌描述文件 UUID 都是本机新文件 `3b616b39-66a1-410d-9906-5205d7047b9e`，包含 7 台登记设备。母包没有 `packCode` 条目；渠道包包含 `packCode/1-218/`。
- 完整模拟器回归：71 项核心 + 15 项 UI 通过，`ios/artifacts/test-run.PDC0Oe/Tests.xcresult`。UI 用例覆盖背景图正常加载与失败回退、首次联网等待、隐私/H5 登录、Bridge 回调、游戏全屏、悬浮球心跳及福利窗口。
- 公共 H5 Bridge/包装器 Node 测试 15 项通过。
- 本机仅有 iOS 18.6 模拟器，没有 iOS 14 运行时，也未连接 iPhone 8 Plus。因此编译与模拟器回归不能替代 iOS 14.4.2 上的安装、网络、渠道上报、H5 登录、授权页及 SIM 取号验收。

## 出包与真机复验

新包用 2026-11-04 更新的描述文件签名；旧构建 8 IPA 或仅替换描述文件都不能解决系统版本限制。用户可先装本轮 `1-218` 渠道包确认是否能打开，再按顺序检查初始化 `Packagecode`、`getGameInfo.data.packageCode`、隐私、H5 登录、一键登录授权、游戏背景图、闪图、悬浮球与福利页。使用自有分包脚本时，以本轮空渠道母包为输入。

| 产物 | 路径 | SHA-256 |
| --- | --- | --- |
| 空渠道母包 | `ios/artifacts/交付_20261104_iOS14兼容_build9_母包/29_com.un74meng.yjhcmc_1.0.1_9_AdHoc.ipa` | `96d1c1acfd95c02d3359dff23c1a5ea4f9fc38d68acc243883b13f50158ee5d4` |
| 渠道 1-218 | `ios/artifacts/交付_20261104_iOS14兼容_build9_渠道1-218/29_com.un74meng.yjhcmc_1.0.1_9_AdHoc.ipa` | `76a668f7a0cbd142d43f74e479da3701668aeb9a32759e4812aac06d3b83491d` |

结构与哈希检查留在 `ios/artifacts/iOS14兼容_build9_最终验证.json`；两产物目录均附归档、dSYM、严格验签摘要与构建日志。原构建 8 配置和 IPA 未覆盖。

截图“关于本机”显示的是序列号，不是用于 Ad Hoc 校验的 UDID；需要从 Xcode 设备窗口或可信设备管理工具读取 UDID，对照**IPA 内嵌**描述文件验证。若安装仍失败，应保留设备端安装错误和 Xcode 设备控制台记录，避免将签名问题误判为系统版本问题。
