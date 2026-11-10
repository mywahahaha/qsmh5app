# iOS开发环境测试与签名出包手册

- 版本：v0.3，按用户收敛充值范围修订的筹备手册；日期：2026-11-02。
- 对应：[总体方案](iOS开发总体方案_ios-development-proposal.md)和[UI设计规范](iOS原生UI设计规范_ios-native-ui-design.md)。
- 当前已创建 `ios/QSMH5App.xcodeproj`，提供共享 Scheme、本地测试和归档脚本。本文件保留完整目标手册；实际已实现的配置、命令和测试以 [iOS开发指南](../ios/开发指南_development.md)及[首期联调记录](iOS首期开发与联调记录_ios-bootstrap-development.md)为准。
- 用户已确认：中国国内、最新说明采用企业签名；本次开发不考虑分发模式。本文将开发环境、功能测试、基础签名构建列为本次范围，分发方式比较及上架资料只作后续参考，不默认 App Store + TestFlight。
- 已确认忽略充值专项、需要隐私撤回、渠道目录读取失败默认空字符串并发送 Packagecode Header；原生只加载 H5 游戏地址，H5 相关跳转遵循统一白名单与导航拦截。阿里云 iOS 2.14.19 已在本机安装并编译链接；最新证书、p12 与 h5App 描述文件已匹配，Ad Hoc IPA 已导出并通过验签（6 台登记设备）。本轮用户自行安装测试，不执行真机验收。

## 1. 人员与开工材料

| 负责人 | 需要准备 | 完成标志 |
| --- | --- | --- |
| 项目/H5 | 单游戏或小游戏平台、H5 游戏地址、白名单、隐私设置入口 | 按总体方案 D02/D05/D11 准备测试材料；分发模式不阻塞本次 |
| Apple 账号持有人 | 可用团队、开发调试签名、必要真机注册与权限 | 可把调试包安装到测试 iPhone；本次不要求 App Store Connect 发布权限 |
| iOS 开发 | Mac、Xcode、Git、可用 iPhone、阿里云 SDK | 可签名安装最小工程并运行官方 SDK |
| 服务端 | ios 平台枚举、iOS 密钥、HTTPS 更新入口、设备码与空渠道 Header 接收 | 测试初始化配置、请求记录与接口样例 |
| H5 开发 | WKWebView 兼容、Bridge、登录、隐私撤回及既有账号能力 | 测试页面、通用导航白名单和可用账号 |
| 测试 | 设备/运营商/网络矩阵与测试记录模板 | 真实 SIM、测试账号与版本记录 |
| 美术/产品 | Logo、更新头图、图标、闪图及 UI 差异确认 | 资产清单与 UI 规范版本 |

不需要为此将 Android keystore、密码或 APK 签名 MD5 交给 Apple 签名流程。现有 Android 证书不用于 iOS。

## 2. 开发环境

### 2.1 Mac 配置建议

| 项目 | 最低可工作建议 | 更合适的团队配置 |
| --- | --- | --- |
| 主机 | 支持所选 Xcode 的 Mac；本机 Intel 已通过基础编译与模拟器测试 | Mac mini / MacBook，M 系列，24GB+ 内存 |
| 内存 | 16GB | 24–32GB，便于并行模拟器与构建 |
| 存储 | 512GB SSD，空闲至少 100GB | 1TB；给 Xcode、模拟器、DerivedData 和归档预留空间 |
| 网络 | 可访问 Apple、阿里云、Git 和测试服务 | 稳定宽带；真机另有蜂窝数据 |
| 调试 | 数据线、受信任 iPhone | 多机 USB 集线器、持续供电、远程可控 Mac |
| 系统 | 与锁定 Xcode 官方支持表匹配 | 受控更新，不随意升级到 beta |

Windows 可继续开发 H5、编辑文档和调用远程 Mac 构建，但不能在原生 Windows 上运行官方 Xcode、iOS Simulator 或完成 iOS 编译签名。云 Mac/自有远程 Mac 可用，仍需符合 Apple 许可并配置真实设备测试。首期推荐开发者直接使用 Mac，减少远程真机与证书问题。

### 2.2 软件与版本锁定

- 从 Apple 官方安装支持目标设备和锁定 SDK 的稳定 Xcode，首次启动完成组件安装；同时确认命令行工具选中同一版本。
- 后续若上传 App Store Connect：2026-11-02 核对的 Apple 要求为自 2026-04-28 上传构建须 Xcode 26+、iOS 26 SDK+；该上传门槛不是本次分发需求，也不意味着最低运行系统必须设为 iOS 26。
- 最低运行系统建议 iOS 16.0，尚待确认；要同时核查当前 Xcode 的部署目标、设备调试和模拟器支持。若锁定工具不能直接调试某最低系统，使用仍支持该设备的官方工具组合，或用可安装签名包补真实运行验证。
- Swift 与 UIKit 使用锁定 Xcode 自带工具链，语言模式先按 SDK 兼容实测锁定；纯 Swift 核心与 Objective-C SDK 隔离。
- Git 必需；Swift Package Manager 优先，锁定 Package.resolved。只有所选官方依赖必须时才安装 CocoaPods，使用受控 Ruby/Bundler；不要同时手工导入和 Pods 引入同一 SDK。
- Mac 本地脚本如使用 Python，建议 Python 3.11+；不依赖系统自带 Python，也不要求迁移 Android GUI 才能开始开发。
- 第一版不强制引入 fastlane、XcodeGen、远程出包服务器；原生 xcodebuild 可重复构建后再引入自动化。

Mac 上的开工检查命令：

~~~bash
xcodebuild -version
xcode-select -p
xcrun --sdk iphoneos --show-sdk-version
xcrun simctl list devices available
git --version
security find-identity -v -p codesigning
~~~

若系统同时装有多个 Xcode，构建任务用 DEVELOPER_DIR 显式指向锁定版本。不要用修改全机选择的方式影响其他项目任务。模拟器下载、Xcode 许可接受、账号登录由开发者在合适的授权上下文完成。

### 2.3 工程构建配置

建议跟踪公共 Base.xcconfig、Debug/Release 配置、共享 Scheme 和非敏感逐游戏配置，环境信息如下：

| 配置 | 用途 | 凭据和地址 |
| --- | --- | --- |
| Debug-Local | 本地服务、Gateway 替身、UI 测试 | 无生产 Token，允许按测试域设置窄范围 ATS 例外 |
| Debug-Integration | 测试服务和真实阿里云 iOS 方案 | 仅测试身份，敏感日志关闭或脱敏 |
| Release | 可重复构建及基础签名验证 | 实际 Bundle ID、业务地址、受控签名引用；不内置分发模式选择器或商店 ID 要求 |

初始化生产主机沿用现有固定地址；本地/测试 endpoint 只通过 Debug 编译配置或测试依赖注入，Release 不能由远程输入任意改到未知服务。不要为兼容旧 HTTP H5 而全局设置 NSAllowsArbitraryLoads；首先完成 HTTPS、证书链、资源混合内容整改。

Info.plist / entitlements 至少核对：Bundle ID、版本/构建号、显示名、方向、设备族、ATS、允许查询的 Scheme、是否需要 Associated Domains、用途描述、Keychain 能力和 PrivacyInfo.xcprivacy。没有实际需求不加推送、后台音频、位置、App Groups 或全量应用探测。

channelCode 从 `.app/packCode` 的直接子目录名读取，目录缺失或不可读时归一化为 ""；初始化、Token 登录、业务兑换、心跳及后续原生业务请求必须显式设置 Packagecode。测试捕获 URLRequest 和受控服务请求，区分“字段存在且为空”与“未发送字段”；Bridge 的 packageCode 使用同一读取值；同时验证渠道 `1-218` 的非空值链路。沿用用户已有分包脚本，不新增发行模式参数。

## 3. 测试环境与矩阵

### 3.1 三层环境

| 层级 | 做什么 | 不证明什么 |
| --- | --- | --- |
| 规则测试 | 配置解析、会话、版本比较、重试、窗口清单、存储转换 | 不证明 SDK 真号码、系统 UI 和真实网络 |
| 模拟器 + 本地 HTTP/HTTPS 服务 | WKWebView/Bridge、导航白名单、Cookie、页面状态、截图、SDK 替身、更新入口替身 | 不证明运营商取号、真实 SIM 或安装服务 |
| 签名真机 + 测试服务 | 运营商、前后台、锁屏、通用外链跳转/返回、性能、基础安装与覆盖更新 | 单台手机不自动覆盖全部系统/运营商；不等于分发上线完成 |

在局域网访问 Mac 服务时，iPhone 使用 Mac 的 LAN 地址，不能把自己的 localhost 当作 Mac。测试如需 HTTP 或本地网络访问，提供 Debug 专属设置与本地网络用途说明（若触发系统权限），不得扩散至 Release。测试证书应正规受信任，不绕过 TLS 验证。

### 3.2 设备组合建议

| 场景 | 设备/环境 |
| --- | --- |
| 最低支持系统 | 可安装所选最低 iOS 的真实设备；实际版本登记 |
| 较小显示区域 | 小尺寸 iPhone / 显示缩放，覆盖长协议和按钮命中 |
| 主流刘海屏 | 真实 iPhone，覆盖 safeArea、横屏与系统手势 |
| 灵动岛/最新系统 | 当前主流设备及稳定系统，覆盖转场和新系统行为 |
| 横屏固定游戏 | landscapeLeft / landscapeRight、听筒两侧 safeArea |
| 竖屏固定游戏 | portrait、键盘、协议详情与返回 |
| 系统外观 | 浅色、深色、VoiceOver、较大字体、减弱动态 |
| 网络 | WLAN+蜂窝、仅蜂窝、关闭蜂窝、无 SIM、双卡默认数据卡切换、飞行模式、弱网 |
| 运营商 | 中国移动、中国联通、中国电信；项目目标地区的实际卡与可用网络 |
| 可选后续 | iPad、分屏、多 Scene；明确启用后才能计入验收 |

一键登录通常依赖设备、SIM、运营商网关及数据网络可用性，不能承诺“只连 WLAN 一定能取号”。按官方 SDK 对真实卡和网络条件验收，记录失败码但不记录认证 Token。

### 3.3 必测用例清单

1. 首装未同意、拒绝后重新选择、隐私撤回、同意后设备码；所有 Header 与 Bridge 值一致。隐私撤回已确认纳入：在途结果失效、本机清理、重启不被 privacyAuto=false 覆盖、清理失败/中断恢复，以及重新同意后的新设备码。
2. Token 成功覆盖、非零清理并跨进程重启验证、网络失败保留、缺 code/空 Token 不成功。
3. 一键登录未开/配置坏、无 SIM、首次授权、协议勾选/链接、切换其他方式、超时、重复唤起。
4. 600001 只回唤起成功；兑换成功后才进入游戏；展示成功回调先送达再关闭 H5。
5. 退出和销毁期间的旧 SDK、旧 HTTP、旧定时器、旧 Bridge 不能改 Token 或重开游戏；兑换真正进入后台时取消并在前台恢复新 H5，Token 已先提交则前台进入游戏，临时 inactive 不误取消。
6. 游戏首次加载/刷新/changeGameUrl 成功清角色，失败不清；通用存储退出后仍在；持久化失败能反馈。
7. 数字和非对象 Bridge 参数、并发 id、晚回调、子 frame、跨域、同名实例重建、外部跳转。
8. GET 四次/五秒恢复，POST 不重放；POST 之后的新 GET 可正常重试；SSL 直接失败。
9. 福利/游戏复用、悬浮球拖动边界、方向变化、运营清单保护功能页、空数组清理和旧响应失效。
10. 后台不轮询、不强持常亮；前台游戏常亮，手动锁屏有效；退出恢复自动锁定。
11. 更新普通/强制、目标版本较旧、配置非法、HTTPS 入口打开失败/返回、未配置入口和新版本重启判断；用受控页面/替身验收，无需完成分发站点。
12. WKWebView 进程终止、内存压力、长时间后台、断网恢复及 Cookie/网页音频恢复。
13. Release 包日志、隐私资源、资源图标、签名、设备可安装性、覆盖升级、重装行为。
14. 通用导航拦截：主文档、重定向、target=_blank/window.open、外部 Scheme 的允许与拒绝；来源、目标白名单和 Bridge 权限隔离。H5 充值相关地址按相同规则处理，不增加专属测试订单或业务验收。
15. 每类原生 API 均发送 Packagecode Header；有渠道目录时返回目录名，缺失时为空，拒绝 null/undefined/控制字符，Bridge packageCode 一致。

### 3.4 测试记录模板

| 字段 | 填写内容 |
| --- | --- |
| 日期/执行人 | 实际测试信息 |
| Git/配置 | 提交、gameId、Bundle ID、channelCode（本次默认空）、实际调试签名引用 |
| 包版本 | CFBundleShortVersionString、CFBundleVersion、包 SHA-256 |
| 工具 | macOS、Xcode、构建 SDK、阿里云 SDK 版本 |
| 设备 | 型号、iOS、显示缩放、系统字体与外观 |
| 网络 | 运营商、数据卡、WLAN/蜂窝、受控弱网条件 |
| 测试项 | 用例编号、步骤、预期、实际与结论 |
| 证据 | 脱敏日志、截图、录屏、xcresult |
| 问题 | 复现条件、严重程度、修复版本与复验结论 |

不得将 Android 已完成真机测试写成 iOS 已验收；iOS 的每个实际测试结果单独记录。

## 4. Apple 账号与签名知识

本次只需掌握并完成开发调试与基础签名构建，不开发多种分发模式适配、企业安装站点、证书代签平台或商店上传。用户的企业签名意向单独记录；下表是原始筹备资料，保留供后续发行负责人参考，不作为本次功能验收清单。

### 4.1 分发方式选择

| 方式 | 适合人群 | 设备与签名 | 更新和限制 |
| --- | --- | --- | --- |
| 免费 Personal Team | 开发者本人初步调试 | Xcode 管理，能力受限；当前资料描述 profile 约 7 天 | 需周期性重建，不能用于正式发行或 TestFlight |
| Development | 团队开发与调试 | Apple Development + 注册设备 + 开发描述文件 | Xcode 安装；启用所需开发者模式与信任 |
| Ad Hoc | 已登记少量测试设备 | Apple Distribution + 含 UDID 的 Ad Hoc 描述文件 | 每产品系列每会员年通常最多 100 台注册设备；不是无限玩家分发 |
| TestFlight | 团队和外部 beta | 上传 App Store Connect，Apple 处理测试分发 | 最长 90 天；内部最多 100，外部最多 10000，外测可能需审核 |
| App Store | 面向普通玩家 | 通过审核的商店构建，Apple 分发 | App Store 更新、地区与上架材料要求 |
| Custom Apps | 特定组织客户 | App Store Connect 与 Apple Business/School 组织分发 | 适合指定组织，不等于公开下载 IPA |
| Enterprise | 符合资格组织的内部员工 | 企业计划与内部分发管理 | 不能面向普通游戏玩家；有严格资格与持续合规要求 |
| 地区替代分发 | 特定地区与符合条件的主体/设备 | 单独的资格、协议、审核/公证与安装条件 | 首期不默认支持，不能外推为全球自由分发 |

标准 Apple Developer Program 官方注册价 99 美元/会员年或当地价格；企业计划 299 美元/会员年，资格不同。组织账号需法律实体、授权联系人、D-U-N-S 等相应材料。会员续期、证书、描述文件与测试构建是不同有效期，分别登记。

### 4.2 一条签名链

~~~text
Apple 开发者团队（Team ID）
  → 显式 App ID / Bundle ID
  → 应用实际需要的 Capabilities / entitlements
  → 证书及配对私钥
  → Provisioning Profile
  → Archive 与对应分发模式导出
  → 设备安装或 App Store Connect 分发
~~~

- Team ID、Bundle ID、签名身份、描述文件和 entitlements 必须匹配。
- .cer 只有证书，不包含私钥；换电脑通常需安全迁移带私钥的 .p12，或在新环境按团队规则创建身份。
- .mobileprovision 描述允许的 App、能力、证书、设备与有效期，不代替私钥。
- 开发者账号权限不等于 App Store Connect 全部发布权限；按 Account Holder/Admin/App Manager/Developer 等实际角色授权，避免共享管理员 Apple ID。
- 自动签名适合本地开发；稳定 CI 可用受控自动签名或手动管理，选择后固定记录。不要随意撤销团队共享证书，可能影响其他项目。

### 4.3 凭据和 CI 管理

.p12、密码、.p8 API 私钥、Keychain 密码不进入 Git、游戏 JSON、构建参数明文日志或产物说明文件。CI 用 Secret Store 与临时 Keychain，构建结束清理任务创建的身份；私钥和临时文件按进程中断后的恢复策略处理。

App Store Connect API Key 与 Distribution 签名私钥用途不同；不能只持有 API Key 就假定能完成所有签名。授权范围、Issuer/Key ID、可用自动签名能力按官方与 CI 实测确认。

保存以下到安全资产登记：证书指纹、用途、团队、到期日期、负责人、续期流程。不要把签名口令放进 Git commit、屏幕截图或命令行参数。

## 5. 出包流程与可复现产物

### 5.1 每游戏输入

| 字段 | 校验和用途 |
| --- | --- |
| gameId | 正整数；与服务端登记一致 |
| gameVersion | 建议 x.y.z；CFBundleShortVersionString |
| buildNumber | 本次上传递增；CFBundleVersion |
| displayName | 桌面名与已登记产品一致 |
| bundleId | 已登记显式 Bundle ID，不能从 Android applicationId 自动推定 |
| teamId | 目标 Apple 团队 |
| channelCode | 默认 ""，省略归一化为空；未来填写值时校验长度及 Header 非法控制字符；签名前写入 |
| orientation | 固定横屏或竖屏，支持方向明确列出 |
| appIcon / splashes | 图像有效、尺寸和透明规则校验 |
| aliyunSchemeRef | 指向 iOS 方案的受控配置引用，不在普通配置保存私钥 |
| signingProfileRef | CI/本机安全资产引用；普通配置不含口令 |

先 CLI 验证可重复构建，再增加 Mac GUI 或 Windows 远程任务入口。现有 Android Tkinter 工具不能直接调用本机 Xcode 编译 iOS；后续如接远程 Mac，只提交经过校验的配置和资源索引，签名材料保留在 Mac 安全环境。

### 5.2 约定命令

以下是后续工程命名约定。destination 中具体模拟器必须使用开工检查列出的实际 UUID，不硬编码某一手机型号。

~~~bash
xcodebuild -resolvePackageDependencies -project ios/QSMH5App.xcodeproj -scheme QSMH5App

xcodebuild test -project ios/QSMH5App.xcodeproj -scheme QSMH5App -configuration Debug-Local -destination 'platform=iOS Simulator,id=实际模拟器UUID' -resultBundlePath artifacts/ios-tests.xcresult

xcodebuild archive -project ios/QSMH5App.xcodeproj -scheme QSMH5App -configuration Release -destination 'generic/platform=iOS' -archivePath artifacts/QSMH5App.xcarchive

xcodebuild -exportArchive -archivePath artifacts/QSMH5App.xcarchive -exportOptionsPlist ios/ExportOptions/Selected.plist -exportPath artifacts/export
~~~

Selected.plist 由持有合法签名材料的负责人提供，匹配实际测试签名；本次不开发分发模式选择逻辑。逐游戏参数由未来构建脚本生成临时 xcconfig/资源副本，不在共享工程目录同时运行会互相覆盖的资源注入。导出选项的 method 按锁定 Xcode 的 xcodebuild -help 生成；不要直接从旧教程复制 app-store/ad-hoc 名称，也不能只改 IPA 文件后缀。

商店上传、远程签名服务和企业安装平台留给后续发行工作；本次输出可复验的归档、实际可导出的测试产物和构建记录。

### 5.3 成品必须包含

~~~text
<BundleID>_<营销版本>_<构建号>/
  应用.ipa                         仅对应模式确有导出时
  归档路径或归档存储引用             .xcarchive
  符号路径或存储引用                 dSYM
  安装包信息.txt                     中文信息，无密码/Token
  构建摘要.json                      配置摘要、提交、工具链、SDK、时间与哈希
  验证日志/                         签名、资源、测试、导出结果，已脱敏
~~~

App Store/TestFlight 最终从 Apple 分发给设备，不能将某个本地导出的 IPA 当作所有用户可安装包。未签名模拟器 app 也不是 iPhone 可安装 IPA。

命名必须无清洗碰撞；目标产物已存在则显式拒绝或使用独立构建目录，不静默覆盖。归档保留用于崩溃符号化、复验与追踪。

### 5.4 出包检查

- 检查 App 的 Info.plist/配置：Bundle ID、营销版本、构建号、显示名、方向、channelCode 默认空。
- 验证签名与实际 entitlements、描述文件的身份和有效期；可用 codesign --verify --deep --strict 和 codesign -d --entitlements :- 辅助检查实际归档 App。
- 用 security cms -D -i 解码存在的 embedded.mobileprovision，核对 Team、App ID、设备与到期时间。Apple 后处理后的分发 App 结构可能不同，不能把“没有本地 profile”一律判坏包。
- 检查 Framework 类型、嵌入签名、缺失资源、arm64 架构、SDK 隐私资源；不能靠 lipo 把真机 arm64 伪装成模拟器 arm64。
- 图标/闪图/Logo/更新头图完整；证书和口令不意外进入 Bundle。
- 至少一次将合法签名测试包安装到测试设备，并覆盖已有版本，核对 Token、隐私、通用存储、空渠道和构建号；不要求完成某个发行平台。
- 未来如做 TestFlight / App Store，再单独验证测试组访问、构建有效期和商店地区可获取状态。

## 6. 后续发布与运营参考（本次不执行）

以下供发行工作启动时使用，不计入本次开发验收；隐私撤回、统一导航拦截及原生请求 Header 已在第 3 节列为本次功能测试。

| 类别 | 发布前必须闭环 |
| --- | --- |
| 主体和资料 | 正确发行主体、Bundle ID、商店数字 ID、地区、年龄分级、资质及联系信息 |
| 内容 | H5 游戏与远程内容清单、可达地址、隐私条款、客服/投诉入口；多小游戏按审核规则补能力 |
| 登录 | 可用审核账号与回退，不要求审核人员具备特定中国运营商才能进入 |
| 隐私 | 数据清单、SDK 隐私政策/PrivacyInfo、Required Reason API、App Store 隐私标签、账号删除 |
| 更新 | 实际更新入口和安装流程可用后再开启强制提示；提供快速关闭更新配置的运营机制 |
| 服务端 | iOS 独立密钥、Header、渠道、设备码、版本和更新地址；对缺字段/非法值有容错 |
| 运维 | 证书/会员/描述文件/测试构建到期提醒、日志脱敏、崩溃符号化、H5 发布回滚 |

H5 可以回滚到已验证内容版本；原生商店二进制一般不能让用户自动降级，问题修复通过新构建和审核流程。保留服务端功能降级/更新开关，但不能把它设计成审核规避开关。

## 7. 预算与排期准备

- 一台支持当前 Xcode 的 Mac、至少一台真实 SIM iPhone 是开工基础；完整验收需要额外机型、系统和运营商测试卡。
- Apple Developer Program、阿里云号码认证调用、短信回退、测试设备、CI Mac 运行、域名/服务与必要的上架材料分别计费。
- 免费账号可跑简单设备调试，不适合长期多人测试和发行；企业账号不是用更高费用换取任意分发。
- 本次开发估算约 19–30 个工作日，包含隐私撤回和统一 WebView 导航拦截；真机调试签名、SDK 和 H5 游戏地址/白名单资料影响 S0，分发平台和审核等待另计。

## 8. 开工检查表

- [x] 明确中国国内、最新企业签名意向；本次不开发分发模式及充值专项，需要隐私撤回。
- [x] 明确 channelCode 默认空字符串，原生业务请求发送 Packagecode Header，Bridge packageCode 一致。
- [ ] 确认单游戏/小游戏模式、H5 游戏地址及通用导航白名单。
- [ ] 确认 Mac、锁定 Xcode/macOS、最低 iOS 与测试设备可覆盖。
- [ ] Apple 团队可用，Bundle ID、证书、描述文件和权限匹配。
- [ ] 获得官方 iOS SDK/Demo/版本/校验信息，真机最小接入可运行。
- [ ] 服务端联调 ios、Devicecode、平台密钥、HTTPS 更新入口及空 Packagecode 的接收记录。
- [ ] 确认 UI 的安全区域、SDK 最小字号与 iOS 更新 dp、百分比、auto 尺寸口径。
- [ ] 梳理 H5 的登录、Cookie、账号删除、通用外链与旧 Android 专属接口。
- [ ] 落实 D11 已确认的隐私撤回需求，联调新增 Bridge 和清理恢复；覆盖兑换后台取消和 Token 已提交两种顺序。
- [ ] 建好测试账号、受控测试服务、设备/运营商矩阵及证据保存位置。
- [ ] 审阅总体方案和 UI 文档，再编写按模块实施的开发计划并开始工程实现。

官方依据链接集中在[总体方案第 14 节](iOS开发总体方案_ios-development-proposal.md#14-官方依据与核对日期)，开工和上传前重新核对时效。
