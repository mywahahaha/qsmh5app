# iOS开发就绪检查

> 本文是创建正式工程之前的历史检查。当前已完成首期工程、SDK 编译接入及模拟器测试；最新结果、签名限制和产物见[首期开发与联调记录](iOS首期开发与联调记录_ios-bootstrap-development.md)。

- 检查日期：2026-11-02（北京时间）。
- 代码基线：`master`，`30d2292`。
- 范围：项目结构、iOS 方案与共用协议、本机工具链、模拟器、签名资料和联调条件。

## 1. 结论

**本机可以开始 iOS 工程开发、基础编译和模拟器测试；目前尚不具备完成全部真机开发、运营商登录和签名安装验收的条件。**

已通过临时 Swift 应用实测确认 UIKit、WKWebView、JS 双向通信以及模拟器安装运行可用。现有 Intel Mac 无须为了启动本项目立即更换；需要先固定 Xcode 路径，并补齐真实 iPhone、匹配的调试签名、阿里云 iOS SDK/认证方案及服务端联调资料。

仓库尚无 `ios/` 产品工程。现有文档足够指导 S0 技术验证和工程骨架设计，但部分跨平台契约仍需收敛，不能直接据此宣称已可完整验收。

## 2. 当前项目与迁移方式

| 部分 | 当前情况 | iOS 下一步 |
| --- | --- | --- |
| `android/` | Kotlin 原生壳，已有启动、认证、WebView/Bridge、悬浮球、心跳、更新和存储模块；主流程由 MainActivity 接线 | 参考业务流程和边界，使用 Swift + UIKit + WKWebView 独立实现 |
| `packager/`、`tools/` | Python Android 出包与渠道工具 | iOS 首期使用本机 Xcode 构建脚本，另建游戏配置及资源映射 |
| `示例/` | H5 调用包装、Axios Header 注入和 Node 回归用例 | 保留 `QSMH5APP` API，新增 WKWebView 原生消息传输与 iOS 来源校验 |
| `docs/` | 总体方案、UI 规范、环境与出包手册、HTML 预览已存在，均以草案/筹备状态记录 | 先收敛接口，再补按模块的实施计划和实际测试记录 |
| `refs/` | 已有阿里云 Android SDK，未发现 iOS SDK/Demo | 获取官方 iOS 包并锁定版本、校验值、架构及 Bundle ID 认证方案 |
| 游戏与 UI 资源 | 游戏图标、闪图、Logo、更新头图已具备参考素材 | 映射到独立 iOS Assets，检查 AppIcon、方向和安全区域 |

已复核 Logo、更新头图 SHA-256，与 UI 规范登记一致。`示例/qsm-jsbridge.js` 是 H5 业务包装，Android `BridgeScript.kt` 的传输入口是 `QSMNative.postMessage`；二者均不能代替 iOS 原生 Bridge 实现。

历史[代码与文档一致性审查](代码与文档一致性审查_code-documentation-audit.md)登记了 Android 会话隔离等问题。iOS 应将这些边界纳入回归用例；本次没有重审或修复全部 Android 问题，也没有修改 Android 专属文件。

## 3. 本机环境实测

| 项目 | 检测结果 | 判断 |
| --- | --- | --- |
| 主机 | MacBookPro15,1，Intel i7-8750H，6 核，16GB RAM | 可以启动首期开发；建议同时运行一个模拟器 |
| macOS | 15.5，24F74 | 与已装 Xcode 16.4 对应兼容 |
| Xcode | `/Volumes/SSD/Applications/Xcode.app`，16.4 / 16F6 | 已安装，首次启动检查通过 |
| 默认命令行目录 | `/Library/Developer/CommandLineTools` | 直接执行 `xcodebuild` 会失败；构建任务需显式设置 `DEVELOPER_DIR` |
| Swift | Xcode 工具链 6.1.2 | 已用 Swift 5 语言模式完成临时应用编译 |
| 编译 SDK | iPhoneOS 18.5、iPhoneSimulator 18.5 | 已分别完成模拟器 x86_64 和设备 arm64 编译 |
| 模拟器运行时 | iOS 18.6，已有 iPhone 16 系列等设备 | 已启动 iPhone 16e 并完成安装运行及 WebKit 检查 |
| 真机连接 | `devicectl list devices` 返回无设备 | 当前不能验证设备安装、LLDB、SIM、常亮或锁屏 |
| 磁盘 | 系统卷约 86GiB 可用，外置 SSD 约 858GiB 可用（检查开始时） | 当前足够开工；建议后续 DerivedData/归档使用 SSD，持续留意系统卷 |
| Node/npm | 24.19.0 / 11.17.0 | 现有 H5 示例测试可运行 |
| Python | 默认 `python3` 为 3.7.0；另有 Homebrew 3.12.13、3.14.5 | iOS 新脚本显式使用 3.12，避免误用旧默认版本 |
| CocoaPods/Ruby | Pods 1.10.1，系统 Ruby 2.6.10，存在 ffi 扩展警告 | 不是首期基础编译的阻塞；只有选定 SDK 集成方式需要时再整理依赖 |
| 业务域名连通性 | 对初始化地址执行 HTTPS HEAD 返回 HTTP 200，系统 curl TLS 校验通过 | 仅证明本机可访问该地址，不证明初始化请求和 iOS 配置正确 |

建议在后续构建脚本中使用：

```bash
export DEVELOPER_DIR=/Volumes/SSD/Applications/Xcode.app/Contents/Developer
xcodebuild -version
xcrun --sdk iphoneos --show-sdk-version
xcrun simctl list devices available
```

本次通过任务环境变量使用完整 Xcode，没有修改全机 `xcode-select`。Xcode 在外置 SSD 上，编译时需要该卷保持挂载。

Apple 官方支持表确认 Xcode 16.4 配套 iOS 18.5 SDK，并支持包含 iOS 16 的部署目标。本次只实际运行了 iOS 18.6；将最低目标设为 iOS 16 不代表已经完成 iOS 16 设备测试，也不证明最新系统兼容。当前没有 iOS 26 SDK，新 SDK/API 需求及其他设备系统需要单独评估工具链。

## 4. 签名资料核对

工作区原有未跟踪目录 `游戏出包/iOS/` 包含：

- `distribution.cer`。
- `74appboxAdHoc.mobileprovision`。

描述文件实际内容如下：

| 字段 | 实际值及含义 |
| --- | --- |
| 名称 | `74-appbox-AdHoc` |
| Team ID | `7BNPHDL54G` |
| App ID 范围 | `7BNPHDL54G.com.un74meng.*`，目标 Bundle ID 仍需明确 |
| 类型 | Ad Hoc；`ProvisionsAllDevices=false`，包含 9 台登记设备 |
| 调试权限 | `get-task-allow=false`，不满足常规附加调试器的开发描述文件要求 |
| 有效期 | 至 2027-08-24；证书也至 2027-08-24，以本次解码结果为准 |
| 证书匹配 | `.cer` 与 profile 内证书 SHA-1 一致，为 `62B635BA5923AB2BD6633A0DDEE6E32B5214BC46` |

本机钥匙串能找到该分发证书，但 `security find-identity -v -p codesigning` 列出的两组有效身份属于另一个团队（`NRDANW7RQN`），不含上述 profile 对应身份。因此当前没有检测到可供该 profile 使用的有效签名身份；可能缺少配对私钥，或私钥不可供当前环境使用。

结论与处理路径：

1. 这些材料实际是 **Ad Hoc**，与文档中用户“企业签名”的意向不同，不能据名称或意向将其当作企业描述文件。
2. 若继续使用 `7BNPHDL54G`，需要配对的可用签名私钥及适合开发调试的证书/profile，并确认测试 iPhone 登记情况；不要仅重复导入 `.cer`。
3. 若使用本机已有身份，需要对应团队的 Bundle ID、开发 profile 和设备权限；账号登录及管理权限本次未验证。
4. Ad Hoc 的有效分发身份可用于适用设备的安装测试；常规断点调试应使用匹配的开发签名。最终仍需实际签名、安装和覆盖验证。

本次没有导入、导出或更改签名身份，没有读取私钥或口令。

## 5. 文档检查：可以沿用与需要补齐的内容

总体方案已覆盖 F01–F14 功能、模块职责、会话代次、WebView 来源隔离、三层测试及 S0–S4 阶段；UI 规范包含安全区域、SDK 最小控件尺寸和异常回退；环境手册区分了模拟器、签名真机和后续发行。可以作为开工依据。

| 缺口 | 证据/影响 | 最晚处理节点 |
| --- | --- | --- |
| 本机硬件描述需要修正 | 环境手册“最低可工作建议”写 Apple Silicon；本次 Intel 已完成基础编译运行，M 系列应列为推荐配置 | 环境基线登记 |
| 缺少真实 iOS 工程与实施计划 | 手册中的 `QSMH5App.xcodeproj`、Scheme、Debug-Local 和导出配置均为未来约定 | 开始 S1 前创建并验证共享 Scheme |
| 新增撤回方法未进入共用 Bridge 清单 | 总体方案设计了 `revokePrivacyPolicy`，共用清单与现有注入脚本尚无该方法 | H5/原生联调前明确参数、回调、来源和清理恢复 |
| 隐私拒绝的平台语义不同 | 共用 Bridge 清单描述“退出应用/无返回”；iOS 方案要求保留说明页，并在迁移表中约定 Result | iOS Bridge 实现前写明差异，避免直接沿用 Android 退出语义 |
| iOS 平台数据仍未确认 | `Devicetype: ios`、随机安装标识、独立 scheme_secret、iOS 初始化配置和更新入口仍待服务端对接 | 真实初始化/认证联调前 |
| 空渠道文案有残留 | 一键登录文档 Header 表仍写 iOS 渠道来源需单独确认，后文和 iOS 方案已明确默认空字符串并发送 | 接口契约收敛时统一 |
| 最新悬浮球规则在 iOS 只被概括引用 | Android 已明确 top 优先、宽高 dp、垂直比例/dp、水平 360 基准；iOS 尚未逐字段定义 pt、安全区、横向换算和旋转/拖动后重算 | 悬浮球编码前形成 iOS 算例及验收条件 |
| 更新尺寸口径已明确 | iOS 纯数字按 dp、百分比按屏幕比例、height=auto 按内容自适应 | 已落地并补充回归测试 |
| H5 测试资料和应用映射未落地 | 缺明确测试入口、账号、Cookie/跨域场景、白名单与 iOS Scheme 映射配置 | S1 联调与相关模块测试前 |
| 最低系统、首个游戏和 Bundle ID 未定稿 | 文档将 iPhone/iOS 16+、单游戏作为建议；尚无实际 iOS 构建配置 | 创建正式产品 target 前 |

其中撤回 Bridge、平台字段、签名身份、真实 SDK/设备优先级最高。仅影响后续模块的事项可以与工程骨架并行收敛。

## 6. 本次实际执行的验证

| 验证 | 结果 | 能证明的范围 |
| --- | --- | --- |
| Xcode 版本、SDK、首次启动状态 | 通过 | 完整 Xcode 可经指定路径调用 |
| Swift + UIKit + WebKit，模拟器 x86_64 编译 | 通过；平台 `IOSSIMULATOR`，SDK 18.5，最低目标 16.0 | 第一方框架编译链接可用 |
| 同一临时应用的设备 arm64 编译 | 通过；平台 `IOS`，SDK 18.5，最低目标 16.0 | 交叉编译可用；尚未做设备签名安装 |
| iPhone 16e / iOS 18.6 模拟器启动、安装、运行 | 通过 | 本机模拟器运行能力正常 |
| WKWebView 内 JS → 原生消息 → 原生执行 JS | 通过，回传 `{"roundTrip":true,"answer":42}` | WebKit 基础双向通信可用，不代表完整 QSM Bridge 已实现 |
| 现有 Node 示例回归 | 7 项通过，0 失败 | H5 包装、Header 注入及现有注入脚本部分行为正常 |
| 真实项目 `xcodebuild test/archive` | 尚不可执行 | 仓库无 iOS 工程，不能将临时探针当作产品验收 |
| 阿里云真号码、Token 兑换、安装/覆盖、锁屏与常亮 | 未执行 | 缺 SDK/方案、连接设备和匹配签名；模拟器不能代替运营商验收 |

Node 检查命令：

```bash
node --test 示例/bridge-script.test.js 示例/qsm-jsbridge.test.js 示例/external-app-bridge.test.js
```

临时探针在系统临时目录构建，测试应用已从模拟器卸载，启动的模拟器已关闭。没有将临时验证代码写入产品目录。

## 7. 建议的下一步顺序

1. **固定开工基线**：构建任务指定 Xcode 16.4；确定首个游戏、Bundle ID、最低 iOS 与方向；补齐跨平台契约和按模块实施计划。
2. **创建最小工程**：Swift + UIKit + WKWebView，共享 Scheme；先实现配置、初始化、隐私、H5 登录和游戏容器，提供本地测试页面及 SDK Gateway 替身；加入单元、Bridge 和 UI 测试入口。
3. **并行补齐真机条件**：连接并信任有真实 SIM 的 iPhone，确认设备系统、开发者模式；准备匹配团队的开发签名；获取官方阿里云 iOS SDK/Demo、版本和认证方案，验证 Intel 模拟器切片。不支持 x86_64 时模拟器构建使用替身，真实 SDK 在 arm64 设备构建接入。
4. **完成一次最小真机链路**：签名安装 → 官方 SDK 授权/取号 → 服务端兑换 → 游戏页；确认 `ios`、Devicecode、空 Packagecode 和独立配置，再进入完整模块开发。
5. **按方案验收完整功能**：会话隔离、福利与运营窗口、悬浮球/心跳、角色与通用存储、隐私撤回、更新入口、加载恢复、安全来源、前后台及常亮；记录真实设备、系统、运营商和构建版本。

可以马上推进第 1–2 步。第 3–4 步未闭环前，完整真机测试和一键登录交付仍有阻塞；原方案的 19–30 工作日为既有估算，待 S0 通过后再复估。

## 8. 官方资料与检查边界

- [Apple Xcode 支持表](https://developer.apple.com/support/xcode/)：本次重新读取，核对 Xcode 16.4、macOS、SDK 和部署目标。
- [阿里云 iOS 客户端接入](https://help.aliyun.com/zh/pnvs/developer-reference/the-ios-client-access)：本次重新读取，静态/动态 SDK 组件、回调和 UI 限制与方案主要描述一致；官方说明动态库不一定包含模拟器架构，最终以收到的包为准。
- [总体方案](iOS开发总体方案_ios-development-proposal.md)、[UI规范](iOS原生UI设计规范_ios-native-ui-design.md)、[环境手册](iOS开发环境测试与签名出包手册_ios-environment-testing-release.md)为本次仓库文档依据。

官方页面通过系统 curl 校验 TLS 后读取；专用网页工具本次返回认证错误。未向生产接口发送业务初始化或登录 POST，未验证 Apple 账号管理权限、真实 SDK 包或发行链路。本次只新增检测报告并补充文档导航，不创建产品工程、不提交或推送。
