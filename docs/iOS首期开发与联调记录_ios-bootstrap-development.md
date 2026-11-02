# iOS首期开发与联调记录

日期：2026-11-02。本文按开发轮次记录实际交付，当前最新为末尾的构建 3；悬浮球专项及最新产物见[对齐记录](iOS悬浮球需求与实现_ios-floating-ball.md)。完整产品目标仍见总体方案。

## 已确认的包身份

- 游戏：全民红月，`gameId=29`。
- Bundle ID：`com.un74meng.yjhcmc`；版本 `1.0.1`，首期构建号 `1`；后续渠道修复构建号 `2`、悬浮球构建号 `3`。
- iPhone 横屏；最低部署目标暂定 iOS 16，最低系统兼容尚未实机验证。
- 初始游戏地址：`https://box.74meng.com/h5/play?gid=29&sid=1&cid=218&app=1&xid=0`。
- 用户明确本轮不做真机测试，由用户后续安装测试；阿里云 iOS 方案后续加入初始化响应。

## 本次实现

工程位于 [ios/QSMH5App.xcodeproj](../ios/QSMH5App.xcodeproj)，使用 UIKit、WKWebView 和 Swift；日常构建无需 CocoaPods 或工程生成工具。

| 模块 | 已实现内容 |
| --- | --- |
| 初始化 | 固定生产初始化地址；六项原生 HTTP Header；严格校验响应、页面、布局和来源；隐私前不生成设备码、不发送登录 Token |
| 隐私 | 同意、拒绝、重新选择、撤回确认；Keychain/WebKit/本地状态清理；撤回中断后继续清理 |
| 登录 | Keychain Token 自动登录；H5 登录成功回调；业务失效清 Token、网络失败保留；退出登录、后台和旧请求响应隔离 |
| 容器 | 配置驱动的协议、登录、游戏、福利页面；横屏安全区域；加载错误、有限 GET 重试、TLS 错误拦截；不自动重放 POST |
| Bridge | 初始化注入、按请求 ID 回调；主 frame、origin、WebView 实例、文档 nonce 和会话代次校验；基础信息、Token、隐私、登录、窗口、切换游戏 URL、存储及认证入口 |
| 阿里云 | 官方真实静态 SDK 编译适配；同意隐私后初始化；凭证只在原生兑换；取消和失败恢复 H5；关闭 SDK 控制台日志 |
| 工具 | 本地 HTTP/H5 替身、模拟器运行与测试、SDK 安装、环境检查、未签名归档和匹配身份签名导出脚本 |

完整 Bridge 范围见 [JSBridge 方法清单第 7 节](../JSBridge方法清单_jsbridge.md)。未实现的方法明确返回不支持；开启更新时提示阶段能力限制，不静默绕过强制更新。

## 本机环境与测试结果

- Intel Mac、macOS 15.5；Xcode 16.4 位于 `/Volumes/SSD/Applications/Xcode.app`。
- 编译 SDK 18.5；iPhone 16e 模拟器 runtime iOS 18.6。
- 脚本通过 `DEVELOPER_DIR` 使用完整 Xcode，没有修改全机 `xcode-select`。

| 验证 | 实际结果与证据（仓库相对路径） |
| --- | --- |
| 核心 XCTest | 首期 28 项通过，0 失败；含后台丢弃晚到 Token 登录响应；`ios/artifacts/test-run.r4u9W4/Tests.xcresult` |
| 模拟器 XCUITest | 7 项通过，0 失败；隐私拒绝与重选、登录持久化、退出、页面错误、URL 切换后 Bridge 和关闭游戏等；`ios/artifacts/test-run.bMZNw8/Tests.xcresult` |
| JavaScript | `node --test ios/Tests/bridge.test.cjs 示例/bridge-script.test.js 示例/qsm-jsbridge.test.js 示例/external-app-bridge.test.js`：11 项通过 |
| 真实 SDK 模拟器链接 | `Debug-Integration` 构建成功；`ios/artifacts/integration-final-build.log` |
| 真实服务页面 | Integration 应用在模拟器成功展示 HTTPS 隐私协议页，横屏内容与按钮可见；截图 `ios/artifacts/交付_20261102_S1_最终/真实服务_模拟器.png`；未点击同意、未执行生产登录 |
| arm64 Release 归档 | 最新源码归档成功；身份、方向、生产测试隔离、SDK 资源检查通过；`ios/artifacts/交付_20261102_S1_最终/` |
| 签名导出 | 新证书、p12 与 h5App 描述文件匹配；Ad Hoc 归档、导出成功，最终 IPA 严格验签及权限、资源检查通过；`ios/artifacts/交付_20261102_S1_AdHoc/` |

UI 测试先于最后的后台 Token 修复完成；该修复后重新运行 28 项核心测试、Integration 构建及 Release 归档。这里没有将不同轮次写成一次全套结果。

真实初始化请求以游戏 29、版本 1.0.1 和空渠道码得到 `code=0`，返回隐私开启、一键登录关闭和更新关闭。0.1.0 被服务端判定作废，故本轮使用 1.0.1。服务端初始游戏链接为 `sid=9&cid=0`，本包采用用户指定地址，并强制其与配置内游戏来源一致。

测试使用本地 H5 登录与认证 Gateway 替身，不代表生产账号登录、真实游戏流程或运营商取号成功。最低 iOS 版本、真实网络切换和设备性能也未完成验收。

## 阿里云 SDK 材料

- 版本：2.14.19；组件：ATAuthSDK、YTXMonitor、YTXOperators。
- ZIP SHA-256：`eb042ae6293c42e4d201a007bc4b74b7d7db8c021c4c956393074ba8e945a38d`。
- 从本机既有官方 SDK 压缩包提取 SDK 目录，未复用其他项目业务配置。
- 二进制、本机 xcconfig 和安装记录均被 Git 忽略；其他电脑须运行安装脚本。
- 供应商调试信息引用缺失的原始 `.pcm` 路径，产生 dSYM 警告但归档成功；完整符号化仍需供应商材料。

操作及边界见 [SDK 接入说明](../ios/Vendor/Aliyun/接入说明_integration.md)。真实密钥、兑换接口和 SIM 认证留待初始化配置补齐后验证。

## 签名检查与包交付

现有 `distribution.cer` 与 `74appboxAdHoc.mobileprovision` 匹配：Team `7BNPHDL54G`，App ID `7BNPHDL54G.com.un74meng.*`，描述文件至 2027-08-24 有效，包含 9 台登记设备。

用户随后提供 `Apple Development biao yang (biao yang).p12`。密码验证成功，但文件只包含私钥 bag，没有证书，且导出的公钥 SHA-256 与 `distribution.cer` 的公钥 SHA-256 不同。因此它不是此分发证书的配对私钥，未导入钥匙串。现有其他团队身份未用于本包。密码没有写入项目文件、文档或命令输出。

后续更新（同日）：用户在 `游戏出包/iOS/证书/` 提供新材料。新 `distribution.cer` 与 `证书.p12` 内证书 SHA-1 同为 `43CCCFDA19CFEC5F4E0585D8E144F89A9A1257A2`；从私钥和证书分别导出的公钥 SHA-256 一致，确认配对。证书 Team 为 `7BNPHDL54G`，有效期至 2027-11-02，钥匙串已存在对应有效身份，无需重复导入。

当时新目录及出包目录没有 `.mobileprovision`；已检查本机两个标准描述文件目录，同团队现存文件均已过期且不绑定新证书。此前检查的 `74appboxAdHoc.mobileprovision` 绑定旧证书，也不能直接沿用新证书。已用新身份签署临时可执行文件并通过 `codesign --verify --strict`，随后自动删除临时文件，确认钥匙串私钥实际可用。当时缺项已收敛为：绑定此新证书、覆盖 `com.un74meng.yjhcmc`（或适用通配符）且包含目标设备的 Ad Hoc 描述文件。当时未生成签名 IPA。

签名命令与校验流程见 [开发指南](../ios/开发指南_development.md)。Ad Hoc 安装仅限描述文件登记的设备。

最终更新（同日）：用户补充 `游戏出包/iOS/证书/h5App.mobileprovision`，已确认绑定新证书，App ID 为 `7BNPHDL54G.com.un74meng.*`，UUID `5f2c68d8-14f5-483a-ac4b-4fba29a9b797`，包含 6 台登记设备；证书与描述文件有效期至 2027-11-02 16:15:15（北京时间）。已使用匹配身份完成 Release arm64 归档及 Ad Hoc 导出。临时安装的 profile 已清理，未改动原有已安装 profile。

最终导出 IPA 经解包后执行 `codesign --verify --deep --strict` 通过；检查叶证书与描述文件证书匹配、Bundle ID/Team/application-identifier 一致、`get-task-allow=false`、无模拟器 Keychain 组、SDK 资源与生产入口隔离。未运行真机安装，签名成功不代表真实登录或取号已验收。

首期签名交付目录：`ios/artifacts/交付_20261102_S1_AdHoc/`，包含：

- `全民红月_1.0.1_AdHoc.ipa`：供描述文件登记的 6 台设备安装。
- `QSMH5App.xcarchive` 及 dSYM；原始导出文件位于 `export/`。
- `构建摘要.json`、`SHA256.txt`、`安装说明.txt`、`archive.log` 和 `export.log`。
- IPA SHA-256：`1cf59b044c8396ece35efda753ab3e67486e7a719acc8976e9de5559c4fdfce1`。

此前未签名交付目录保留为 `ios/artifacts/交付_20261102_S1_最终/`，包含：

- `QSMH5App.xcarchive` 及 dSYM。
- `全民红月_未签名需重签.ipa`：**不能直接安装到 iPhone**。
- `构建摘要.json`、`SHA256.txt`、`安装说明.txt` 和归档日志。

产物与 SDK 二进制不入库。代码和文档尚未提交、推送。

## 后续范围

角色数据、心跳运营窗口同步、原生更新 UI、常亮、正式品牌授权页、完整图标与闪图资产映射、多游戏出包尚未完成。悬浮球与心跳 `floating_ball` 已于构建 3 接入，见[专项记录](iOS悬浮球需求与实现_ios-floating-ball.md)。真实一键登录、真实账号及游戏流程、最低系统兼容和安装覆盖测试需后续联调。Android 代码本轮未修改，其测试结论不沿用为 iOS 结论。


## 渠道读取与 H5 兼容性更新（2026-11-02，构建 2）

- 新增 `Core/ChannelCodeReader.swift`，从主 Bundle 父目录枚举 `.app/packCode` 的直接子目录，目录名作为渠道码；所有缺失/读取错误返回空，不阻塞业务。移除旧 Info.plist 渠道字段和编译变量，原生 Header 与 Bridge 共享启动时读取的 `GameIdentity`。
- 使用用户样本 `/Users/cyang/Downloads/qmhy_1-218.ipa` 解包验证，原生 Swift 读取器、生产 `RuntimeConfiguration` 和 `Packagecode` Header 均得到 `1-218`。样本自身严格验签通过，原文件未修改。验证工具仅在本机执行读取逻辑，不执行 IPA 的 iPhone 二进制。
- 新母包渠道为空；临时副本创建 `packCode/1-218` 后同一读取链路得到 `1-218`，副本严格验签通过，临时副本随后清理。母包和样本验证明细位于新交付目录 `渠道读取验证.json`。
- XCTest：8 项渠道边界 + 29 项核心全部通过，含 `.app` 改名、多应用、目录缺失、普通文件、符号链接、非法 Header 字符、旧 plist 不回退，以及隐私前后 Bridge/HTTP 渠道一致性。证据：`ios/artifacts/test-run.ibaGJm/Tests.xcresult`。
- 本轮选取两项 UI 回归：窗口复用/URL 切换/Bridge/关闭游戏通过；空渠道 Header 用例首次在协议到登录页切换等待失败，单独复核通过，未复现该失败。复核证据：`ios/artifacts/test-run.4nkFMO/Tests.xcresult`。未将首次有失败的测试批次记录为全套通过。
- JavaScript：`node --test ios/Tests/*.cjs 示例/bridge-script.test.js 示例/qsm-jsbridge.test.js 示例/external-app-bridge.test.js`，15 项通过。新增比较两端注入器与同一 H5 Promise/Axios 包装的测试，验证平台枚举、渠道 Header、调用签名和存储值归一化。Android 仅只读核对源码，本轮未修改或运行 Android 构建。
- 同步将 iOS `notifyLoginSuccess`、窗口打开/关闭、`closePage` 的成功结果对齐为 `data=true`；存储 JS 入口对非字符串/数字值统一为空字符串。完整平台差异和 H5 用法见 [JSBridge 清单第 8 节](../JSBridge方法清单_jsbridge.md)。基础调用无需拆分两套；角色/悬浮球/第三方应用、隐私撤回、窗口范围与同源导航仍需遵循当前能力边界。
- 新签名母包：`ios/artifacts/交付_20261102_渠道读取修复_AdHoc/全民红月_1.0.1_2_母包_AdHoc.ipa`，SHA-256 `eae3e3fa6a0a20a5c70b7ee4594b5d282c064fc8944d6709a4bc3a62f6bc0267`。归档、导出、最终 IPA 严格验签、证书/profile/权限、资源与生产隔离检查通过。只限描述文件登记的 6 台设备，未执行真机安装。

需要以构建 2 母包重新分包，旧 IPA 内二进制不会因已有目录而自动更新。旧交付目录作为历史保留，不覆盖用户的分包样本；代码和文档仍未提交或推送。

## 悬浮球对齐更新（2026-11-02，构建 3）

- 对照当前 Android 源码接入悬浮球状态机、布局、纵向拖动、点击福利、福利覆盖暂隐及恢复，H5 `setFloatingBall` 调用通用；pt 和 safeArea 为 iOS 坐标适配。详见[专项记录](iOS悬浮球需求与实现_ios-floating-ball.md)。
- 心跳首次显示后完整等待、单请求串行、隐藏后继续、后台暂停/前台恢复、退出丢弃旧响应；本轮仅处理 `floating_ball`，未接入 `popup_pages` 运营清单。
- 修正游戏换地址后普通置顶/福利返回意外重新加载初始化 URL 的问题，复用窗口并保留当前文档。
- 核心测试 51 项通过，最新复核 `ios/artifacts/test-run.tyFGXR/Tests.xcresult`；完整 UI 回归 10 项通过，`ios/artifacts/test-run.gADywd/Tests.xcresult`；JavaScript 15 项通过。追加文档复用专项断言后的复核通过；最终全屏截图专项通过，证据 `ios/artifacts/test-run.N4SxL5/Tests.xcresult`，三张截图在新交付目录 `全屏截图/`。
- 新签名母包：`ios/artifacts/交付_20261102_悬浮球_AdHoc/全民红月_1.0.1_3_母包_AdHoc.ipa`；SHA-256 `4f0b121e9d26c4b97db92360eaf2b4d4c0af98cc13a900d63f51ef6849a40b01`。最终 IPA 严格验签及身份/profile/权限、方向、SDK 资源和生产隔离验证通过。
- 当前最新为构建 3，替代上节构建 2 进行后续分包；历史产物保留。仍只限 profile 登记的 6 台设备，由用户自行安装；未执行真机、真实账号或取号测试。Android 本轮仅只读核对。
