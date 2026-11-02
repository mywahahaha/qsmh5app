# iOS开发总体方案

- 版本：v0.3，按用户收敛充值范围修订的方案草案；日期：2026-11-02。
- 项目：qishimeng_h5_sdk；Android 已由用户确认完成真机测试，iOS 当前尚无工程和 SDK 交付包。
- 本次交付：开发设计、UI 规范、环境与签名出包手册、界面预览；尚未创建 iOS 产品工程。
- 用户明确要求：功能对齐 Android；一键登录、在线更新等原生 UI 保持一致，并形成可供后续开发的文档。
- 已确认：目标中国国内；用户最新说明采用企业签名，本次开发不考虑分发模式及充值专项；需要隐私撤回；渠道码默认空字符串，并加入原生请求头。游戏由 WKWebView 加载 H5 地址，H5 充值相关跳转统一遵循既有白名单与导航拦截规则。
- 技术选型、最低系统及其他未确认项见第 13 节。不得将建议默认值视为用户已确认。

## 1. 推荐路线与阅读顺序

推荐用 Swift + UIKit + WKWebView 开发独立 iOS 壳工程，与 Android 共用业务接口、H5 调用契约和美术资源，分别实现平台适配。UIKit 便于接入阿里云 Objective-C SDK、定制弹窗与控制多 WebView 生命周期。

本次以功能对齐、可签名真机调试和基础构建为交付范围，取消先前 App Store + TestFlight 的默认路线。最新“企业签名”说明覆盖较早的 Development / Ad Hoc 内测选项；不要求本次实现分发模式选择、商店上架、TestFlight、企业分发站点或远程签名服务。

签名背景仍需准确记录：Apple Enterprise 官方适用范围为符合资格组织的内部员工，企业签名本身不代表面向普通玩家分发的授权；分发资格由后续发行环节处理，不作为本次功能设计的阻塞项。

配套资料：

1. 本文：范围、功能迁移、架构、接口与实施阶段。
2. [iOS原生UI设计规范](iOS原生UI设计规范_ios-native-ui-design.md)：布局、资源、原生页面状态与 Android 差异。
3. [iOS开发环境测试与签名出包手册](iOS开发环境测试与签名出包手册_ios-environment-testing-release.md)：Mac、真机、账号、证书、测试及发布操作。
4. [iOS原生界面预览](iOS原生界面预览_ios-native-ui-preview.html)：可切换授权、协议、兑换、更新、隐私撤回及网络错误等状态的设计预览，不连接真实 SDK 或服务端。

本方案以现行 Android 业务需求和已核实实现为参考。iOS 专属限制以本文及 UI 规范为准；待双方确认后，再同步共用文档，不直接覆盖 Android 的 APK 更新和包名检测规则。

## 2. 必须先区分的平台能力

| 项目 | Android 当前方式 | iOS 推荐方式 |
| --- | --- | --- |
| 游戏页面 | Android WebView | WKWebView；WebKit 随系统更新，不打包 Android 内核 |
| 一键登录 | 阿里云 Android AAR、Android 认证方案 | 阿里云 iOS SDK、独立 Bundle ID 认证方案；业务兑换接口可共用 |
| 原生更新提示 | 自定义弹窗 | 同品牌自定义弹窗，校验后打开 HTTPS 更新说明/安装引导页 |
| 下载并安装新版 | 下载 APK、FileProvider、系统安装器 | 本次仅交接更新入口；安装由外部系统流程处理，不自行安装或替换 IPA |
| 渠道分包 | 修改 APK Signing Block，无需重签 | 本次不开发渠道分包；channelCode 默认空，仍发送 Packagecode Header |
| 设备标识 | OAID / ANDROID_ID / DIY | 默认建议隐私同意后生成应用内随机安装标识，不读取 Android 标识 |
| 应用检测 | Android packageName | 系统 URL Scheme / Universal Link 的有限能力，不支持枚举任意 Bundle ID |
| 保持亮屏 | FLAG_KEEP_SCREEN_ON | 前台游戏期间设置 isIdleTimerDisabled，结束或退后台恢复 |
| 拒绝隐私 | Android 可结束 Activity | 不调用 exit 退出；显示隐私说明与重新选择入口，由用户自行离开应用 |
| 后台心跳 | 受 Android 生命周期限制 | 进入后台停止轮询并失效旧响应，回到前台按间隔重新安排 |
| 系统设置 | 可打开网络设置页面 | 只使用公开设置入口；不能承诺直接打开 WLAN/蜂窝开关页面 |

“保持一致”指业务含义、品牌、可观察状态、参数与回调一致。系统安装页、运营商协议控件、手势和安全区域按 iOS 公开能力实现。

## 3. 首期功能范围与验收目标

首期建议支持 iPhone，最低 iOS 16.0；Mac 建议 Apple Silicon。是否支持 iOS 16、iPad、横竖屏临时切换，须结合实际设备与阿里云 SDK 做开工确认。最低运行系统与构建所需 Xcode/SDK 是不同指标。

| 编号 | 功能 | iOS 交付要求 | 验收证据 |
| --- | --- | --- | --- |
| F01 | 游戏构建身份 | gameId、版本、显示名、Bundle ID、方向和资源编译进包；channelCode 默认空；可复用模板 | 两份样例配置的配置校验与包内回读 |
| F02 | 启动与初始化 | POST 固定初始化地址，六项 Header；非零码显示服务端中文提示 | URLSession 替身、测试服务及真机 |
| F03 | 启动闪图 | LaunchScreen 静态启动画面 + 应用内闪图；方向匹配、等比裁切；1.2 秒最短、5 秒最长沿用 Android | 录屏、快慢初始化测试 |
| F04 | 隐私 | 同意前不初始化取号、不生成设备码，原生请求账号与设备 Header 为空；同意后共享同一状态 | 首装、拒绝、同意、撤回与重启 |
| F05 | 登录 | 本地 Token → 一键登录 → H5 回退；H5 成功通知后安全保存并开游戏 | 三条成功路径与异常路径 |
| F06 | WebView / Bridge | 保持 QSMH5APP、异步 Result、Ready 事件、实例与来源隔离 | 共用契约用例、真实 WKWebView |
| F07 | 福利与窗口 | 实例复用、置顶、游戏状态保留、地址切换、关闭即失效 | 页面计数、JS 状态、生命周期回归 |
| F08 | 悬浮球 | 可拖动、运行态显隐/激活、图标缓存、避让安全区、福利覆盖时暂隐 | 横竖屏与边界拖动截图 |
| F09 | 心跳 | 完整间隔后首次请求；单请求串行；按运营清单同步并保护功能实例 | 定时器替身、本地 HTTP、后台恢复 |
| F10 | 角色和通用存储 | 按 gameId 全量替换角色；游戏主文档成功后清角色；通用存储跨页面共享、退出保留 | 重启、写入失败、批量原子性 |
| F11 | 更新 | 原生更新 UI 对齐，HTTPS 更新入口交接；错误放行；本次不实现各分发渠道安装器 | 跳转替身、取消、返回、不可达 |
| F12 | 常亮与恢复 | 游戏成功显示后前台常亮；退出、关闭游戏、退后台恢复；手动锁屏有效 | 静置超过自动锁定时长的真机视频 |
| F13 | 外链与应用能力 | 域名白名单、外部打开；iOS 应用能力只对白名单映射开放 | Scheme 配置、失败回调、来源拒绝 |
| F14 | 隐私与既有账号能力 | 隐私清单、SDK 数据声明、撤回入口；账号删除按既有服务端能力对接，不与 logout 混用 | 撤回清理、重新同意及现有账号流程 |

充值不属于本次功能需求、原生 UI、接口对接、测试或排期。原生侧只加载 H5 游戏地址；H5 充值页面及业务调整由 H5 自行负责，其导航与外部跳转使用第 7.1 节的统一约束，不增加充值专属白名单豁免或原生能力。

## 4. 启动、会话与生命周期

### 4.1 主流程

~~~mermaid
flowchart TD
    A[读取本次构建配置与本地闪图] --> B[请求初始化]
    B -->|失败| E[显示初始化错误与重试入口]
    B -->|成功| C{需要启动隐私确认}
    C -->|需要| D[隐私 H5]
    D -->|拒绝| R[隐私说明页 等待用户选择]
    D -->|同意| U[检查 iOS 更新配置]
    C -->|已同意或按配置跳过| U
    U -->|跳过或异常放行| T{存在本地 Token}
    U -->|正常更新| V[原生更新弹窗 打开更新入口]
    V -->|允许继续| T
    T -->|有| L[Token 自动登录]
    L -->|成功并保存新 Token| G[创建游戏实例]
    L -->|失败| O{一键登录可用}
    T -->|无| O
    O -->|可用| N[原生授权与业务兑换]
    O -->|关闭或不可用| H[新的 H5 登录页]
    N -->|成功并保存 Token| G
    N -->|取消或失败| H
    H -->|通知登录成功| G
    G --> K[主文档成功 常亮与角色清理]
    K --> P[悬浮球及运营心跳]
~~~

privacyAuto=false 沿用 Android 的当前业务含义：跳过隐私 H5 并本地标记同意。这一远程开关不能替代适用隐私法规要求；iOS 正式分发前应确认现有隐私页和同意采集方式。账号删除与撤回入口不能只依赖首次启动弹窗。

### 4.1.1 隐私撤回（需求已确认，接口为新增设计）

用户已确认需要隐私撤回。在可信 H5 的“账号/隐私设置”提供“撤回隐私同意”；H5 调用新增的 revokePrivacyPolicy 后，由原生展示二次确认框，说明将结束游戏并清理本机数据，只有用户确认才开始执行。当前共用 Bridge 尚未实现此方法，后续与 H5 同步接口并新增 iOS 实现。

设计调用为 QSMH5APP.revokePrivacyPolicy({}, callback)，使用既有 Result 包装。用户取消返回 code=0、data=false，完整保留当前游戏与存储；只有清理完成才返回 code=0、data=true。参数非法返回 1001、data=false，执行/清理失败返回 9999、data=false。仅可信且仍有效的隐私设置来源可调用，每次只允许一个确认/清理流程，每笔请求结果最多一次；离开页面不代表用户已同意撤回。

- 串行写入 revoked 状态，立即停止 SDK、请求、心跳与常亮，递增会话代次，拒绝后续受保护 Bridge 调用；保留本笔撤回请求的唯一结果回调权限。
- 清理本 App 的业务 Token、随机 Devicecode 原文、角色与通用存储，以及专属 WKWebsiteDataStore 中的 Cookie、Web 存储和缓存。保留包内配置与美术资源；不删除其他 App 数据，也不代表服务端账号注销。
- 完成落盘和数据清理后回调 code=0、data=true，随后关闭全部业务页面并显示隐私说明。清理失败回调 9999、data=false，仍保持 revoked、禁止重启认证，由原生说明页提供重试；不能以清理失败恢复采集。
- revoked 优先于 privacyAuto=false，重启不得自动重新同意；用户主动进入协议并明确同意后才生成新安装标识、重新初始化和登录。服务器已保存数据的删除走独立账号删除/权利请求流程。
- 需保存可恢复的清理任务标记，进程中断后继续清理；直到完成且重新同意前，六项 Header 中账号与设备值仍为空，不开放业务页面。

上述为本方案的清理设计，需在实施时同步共用接口文档；不代表 Android 已有此实现。普通 logout 仍按下一节保留通用存储与同意状态，二者不能混用。

### 4.2 会话隔离作为基础设计

SessionCoordinator 统一持有 sessionEpoch。启动认证、业务兑换、HTTP 响应、定时器和 Bridge 消息均携带该代次；logout、重新初始化、根控制器释放时递增。

Token 的“代次检查 + 写入”必须在同一串行会话状态边界内完成，不能先检查、异步写入时被新会话插入。旧请求即使取消失败，也不得写 Token、回调旧 WebView、创建游戏或改变新窗口。

logout：清除并确认 Token 删除成功 → 关闭当前会话全部页面 → 隐藏球、停止心跳与常亮 → 从一键登录分支重新开始。保留通用存储与隐私同意，不重新初始化，不尝试旧 Token。

requestPhoneLogin 的“授权页已展示”回调与“业务登录成功”分开。先将 code=0、data=true 送达原 H5，再关闭对应登录实例；之后认证结果只驱动原生流程。每笔请求只回调一次；页面关闭后来源身份立即失效，不等淡出动画结束。

### 4.3 iOS 生命周期

- 首期单 Scene；前台期间心跳按间隔运行，后台取消计时和在途结果的应用权限，不依赖后台持续执行。
- 回到前台：恢复仍有效的游戏状态与常亮；心跳重新等待完整 interval，不补发后台积压请求。
- 锁屏、电话、控制中心、运营商授权和外链返回都要覆盖。退出 App 不等于 logout。
- 认证期间区分临时 inactive 与真正 didEnterBackground：控制中心、系统弹窗等暂时失活不立即取消认证。真正进入后台时，若尚未原子提交业务 Token，则失效本轮认证代次、取消 SDK/兑换、丢弃临时凭证并标记 resumeToH5；下次前台创建新的 H5 登录，不自动反复弹授权或重放兑换 POST。
- Token 提交与后台事件由同一串行协调器排序：提交先完成则保留已登录态，待前台显示游戏；后台失效先发生则旧响应不能再保存 Token。任一路径都结束等待态，避免原 H5 已关闭后留下空白页。
- 不调用私有 API 强制转屏。Scene 支持的方向由构建配置确定；如需授权方向与游戏方向不同，使用公开方向请求并实测 SDK 呈现。失败时保持可操作布局或回退 H5，不强行改 UIDevice 私有属性。
- iPad、多窗口首期不启用；未来启用须单独处理视口重算、应用级常亮所有权和 SDK 单例认证竞争。

## 5. 架构、目录与模块职责

建议在现有仓库新增 ios/，保留 Android、Python 工具与既有文档；不尝试把 Kotlin View 或 AAR 直接转换为 Swift。

~~~text
ios/
  QSMH5App.xcodeproj/               Xcode 工程与共享 Scheme
  App/                             AppDelegate、SceneDelegate、容器控制器
  Config/                          公共与逐游戏 xcconfig、Info.plist、entitlements
  Games/                           无密钥游戏配置与资源索引
  Resources/                       Assets.xcassets、LaunchScreen、PrivacyInfo.xcprivacy
  Vendor/Aliyun/                    官方 SDK、版本与校验记录（分发许可确认后）
  Packages/QSMCore/                 纯 Swift 规则与契约测试
  Features/
    Bootstrap/                     初始化、隐私、启动编排
    Auth/                          阿里云适配、Token 登录、会话
    Web/                           WKWebView 实例、导航、Bridge
    FloatingBall/                  悬浮球和图标缓存
    Heartbeat/                     串行轮询与运营窗口同步
    Update/                        更新策略、原生弹窗、HTTPS 入口交接
    Storage/                       Keychain、原子 JSON 持久化
  Tests/                           单元与契约用例
  UITests/                         本地页面及真实 WKWebView 回归
  ExportOptions/                   受控的非敏感签名导出配置，不做模式选择器
  Scripts/                         配置校验、构建、成品检查
~~~

| 模块 | 输入 | 输出和边界 |
| --- | --- | --- |
| BuildConfiguration | xcconfig / 游戏 JSON | 只读游戏身份、channelCode（默认空）和资源 |
| BootstrapCoordinator | 配置、隐私状态、初始化响应 | 唯一启动状态机；错误和重试不并发 |
| SessionCoordinator | 登录事件、logout | Token 写入和会话代次的唯一入口 |
| AliyunAuthGateway | 当前 iOS 认证配置、宿主 VC | presented / credential / cancelled / failed；不直接持久化业务 Token |
| APIClient | 请求身份、隐私状态、会话代次 | URLSession，HTTP 状态、字段存在和类型严格校验 |
| WebContainer | PageSpec、可信来源 | 实例注册、生命周期、尺寸、消息身份 |
| BridgeDispatcher | id、method、args、来源和代次 | 按原 id 返回一次 Result；保留错误参数类型 |
| UpdateCoordinator | iOS 更新配置和获准 HTTPS 更新入口 | skip / offered / opening / waitingReturn / finished |
| Storage | 类型化读写请求 | 明确返回持久化结果，不把内存更新等同于落盘成功 |

UI 使用 UIKit、Auto Layout 与必要的 CAShapeLayer；网络优先系统 URLSession；图像优先 URLCache + NSCache；第一版不引入跨端 UI 框架。Swift Package Manager 管理源码依赖，阿里云按实际官方二进制包集成。

## 6. 共用接口与 iOS 增补契约

### 6.1 六项 Header

| Header | iOS 建议来源 | 状态 |
| --- | --- | --- |
| Gameid | 构建 gameId | 沿用 |
| Gameversion | CFBundleShortVersionString 对应的 gameVersion | 建议版本统一为 x.y.z；CFBundleVersion 独立递增 |
| Packagecode | channelCode，默认空字符串 | 用户已确认；即使为空也显式设置该 Header，不省略、不发送 null |
| Devicetype | 建议固定 ios | 服务端确认前不能冒充 android |
| Devicecode | 同意隐私后生成并保存的随机安装标识之 MD5 小写 32 位 | 新平台方案，需服务端确认 |
| Accountoken | 从安全存储读取当前有效 Token | 拼写保持 Accountoken；同意前为空 |

POST 初始化固定地址：https://box.74meng.com/h5_app/init，请求体 {}。Token 自动登录沿用 /h5_app/login_by_token；一键登录兑换取当前 iOS 配置的 exchange_url，body 为 auth_token。URLSession 原生接口注入六项 Header；不能假定 WKWebView 的所有页面、子资源和 JS fetch 自动带这些 Header。

channelCode 在配置缺失时归一化为 ""。初始化、Token 登录、一键登录业务兑换、心跳及后续原生业务 API 统一通过 APIClient 设置 Packagecode；测试既断言 URLRequest 中存在该字段，也核对受控 HTTP 接收端实际收到空值。不得发字符串 "null"、"undefined"、空格占位，或将渠道码伪装成签名类型。getGameInfo 中现有 packageCode 字段同步返回空字符串，不更改共用字段名。本次不做渠道分包或安装后归因。

HTTP 非 2xx、缺少 code、code 类型错误、Token 缺失或空白均不能判成功。可选的一键登录配置不可用时回退 H5，不能让关闭状态下的坏配置阻断初始化。所有尺寸必须是有限数，明确拒绝 NaN、Infinity、越界和非法 Center 用法。

### 6.2 平台配置如何下发

推荐服务端依 Devicetype 返回对应平台内容，保持 aliyunOnekeyConfig、h5Pages、floatball、popupInterval、updateInfo 等根字段；iOS 必须收到独立 scheme_secret、iOS 更新目标和可在 WKWebView 工作的 H5 地址。

本次沿用 updateInfo 的视觉字段和 url；可选 targetBuild 是增补建议，尚未与服务端确认。不要求新增 distribution、appStoreId，也不由它们决定本次开发范围：

~~~json
{
  "open": 1,
  "width": "80",
  "height": "auto",
  "force": 0,
  "title": "邀您体验新版本",
  "desc": "发现新版本，体验优化",
  "time": "2026-11-02",
  "version": "1.1.0",
  "targetBuild": "110",
  "url": "https://受控更新域名/对应游戏的更新说明页"
}
~~~

- url 本次约定为包内/受控配置获准域名的 HTTPS 更新说明或安装引导页，使用系统浏览器打开；未提供有效入口时提示后放行。具体安装站点、manifest、企业安装链路不属于本次实现。
- Android 的 popup=0 内部下载 APK 不适用于 iOS。iOS 不把 popup=0 解释为下载 IPA；更新按钮统一交接上述入口，不在高权限 WKWebView 内加载安装页。
- version 是展示与营销版本；targetBuild 若提供须符合构建号规则。是否可更新先验证目标确实较新，不能按字符串字典序比较。
- force=1 只禁止正常提示框主动关闭，跳转或配置异常仍提示并放行，沿用当前 Android 容错；实际安装入口可用性由发行方保证。本次用受控页面及打开失败替身验收，不依赖完成分发站点。
- 系统接受打开入口不表示已安装；回到 App 不伪造下载或安装进度。非强制可关闭后继续登录，正常强制状态保留提示；已安装新包后重新初始化决定是否仍需更新。
- H5 内容更新与原生二进制更新独立；本次不设计原生代码热更新。

### 6.3 Bridge 迁移表

| 方法组 | iOS 行为 |
| --- | --- |
| agreePrivacyPolicy / rejectPrivacyPolicy | 保持 Result；拒绝后取消认证并转隐私说明，不能程序退出 |
| revokePrivacyPolicy（新增设计） | 用户已确认需要；可信隐私设置入口触发，按 §4.1.1 清理并结束会话；当前尚无实现 |
| requestPhoneLogin / notifyLoginSuccess | 与共用一键登录文档相同的回调边界、成功条件和会话保护 |
| getAccountToken / getGameInfo / logout | 一致；deviceType 为确认后的 iOS 值，gameVersion 取当前包 |
| saveGameRole / getGameRole | 一致；角色校验、替换、游戏文档成功后清理；磁盘失败可反馈 |
| saveLocalStorage / getLocalStorage | 一致；字符串与数字转换、整批校验、退出保留；应用容器数据随卸载清除 |
| setFloatingBall | 一致；由当前游戏状态和福利覆盖约束最终显隐 |
| openMiniWindow / closeMiniWindow / closePage | 一致；关闭仅当前实例，游戏关闭停止会话相关显示 |
| changeGameUrl | 当前游戏可信主机精确匹配，复用实例；true 只表示接受导航 |
| isAppInstalled / openApp | 保留方法入口；已有 packageName 先经包内白名单映射到登记 iOS Scheme / Universal Link；未映射返回 1001、data=false |
| on / off | 延续当前预留状态；不宣称实现未定义事件 |

应用能力提案：包内维护 externalApps 映射，H5 继续传业务目标的现有 packageName 字符串，该字符串只作映射键，不在 iOS 查询安卓包。canOpenURL 只能探测登记 URL Scheme 的处理能力，不能证明某 Bundle ID 一定安装；Universal Link 使用公开 open 的 completion 判断系统是否接受。需要新增目标时重新出包。若无需 iOS 该功能，可在确认后统一返回明确的“不支持”错误，不用成功 false 掩盖未实现。

## 7. WKWebView、安全与持久化

### 7.1 页面与消息

- 使用 WKUserScript 在 documentStart、主 frame 注入兼容脚本，适配 window.webkit.messageHandlers.qsm.postMessage；H5 仍只见 QSMH5APP。
- 幂等保留请求回调表；通过页面正常脚本世界调用完成回调。传参使用结构化 JSON 序列化，不能拼接未经转义的业务文本。
- WKScriptMessage 的 frameInfo.isMainFrame、securityOrigin、当前 WebView 对象、document/navigation 代次、sessionEpoch 共同校验；即使注入逻辑被绕过，原生入口仍拒绝越权。
- 默认生产只用 HTTPS；来源精确匹配协议、主机与端口。配置域名、游戏导航域名、jumpDomain 和应用外链白名单分别管理。跨域跳转不继承 Bridge 权限。
- 心跳运营页面只信任初始获准来源，不允许用功能实例名提升权限。普通动态窗口不自动获得 Bridge。
- target=_blank / window.open 只在用户触发且命中外链白名单时用系统浏览器打开；不为外部页面复用高权限 WebView。
- 普通页面导航、重定向、新窗口与外部 Scheme 均经过统一导航拦截；按既有规则判定容器内加载、获准外部打开或拒绝。H5 充值相关地址遵循相同规则；未获准目标保持拦截，不能因业务用途放行，也不自动加入白名单。
- 页面关闭立即移除处理器、定时器和实例身份；使用弱引用消息转发器，避免 WKUserContentController 与 VC 互相持有。

### 7.2 加载与恢复

主文档成功采用 WKNavigationDelegate didFinish 作为 iOS 提交完成标志，不照抄 Android onPageCommitVisible；成功仅在当前导航 id、合法响应且未失败时生效。子资源及业务 fetch 失败不替换整页。

明确 GET 请求：共四次尝试、每次最多五秒、失败立即下一次；SSL 不绕过且不重试，普通 4xx 不重试，408/429/5xx 可重试。非 GET 不自动重放；独立新 GET 重置重放安全状态。WKWebView 进程终止用 webViewWebContentProcessDidTerminate 进入受控恢复，保护页面重复提交。

共享 WKWebsiteDataStore.default 供同一应用实例使用，避免登录与福利 Cookie 隔离；Cookie / SameSite / ITP / 第三方 Cookie 的实际兼容必须用 iPhone 验证，不承诺与 Chromium 完全一样。涉及 SSO 的 Cookie 同步需定义清楚，不能将账号 Token 写 URL。

### 7.3 数据与隐私

- 业务 Token：Keychain，推荐 WhenUnlockedThisDeviceOnly；明确 service/account 命名，不写日志、URL、普通配置或剪贴板。
- 角色与通用存储：应用容器内原子文件写入，后台串行 I/O；回调前确认写入结果。仅删除角色数据时不影响通用存储。
- Devicecode：建议随机 UUID 原文只在应用容器保存，对外只用约定哈希；不读取 IDFA、IDFV、MAC 或通过硬件/网络指纹拼接标识。MD5 仅适配现有字段格式，不作为加密。
- Keychain 不保证随卸载清除。使用不参与备份的安装标记区分新安装；标记缺失时清理本 App 的旧账号 Keychain 项并重新走隐私，不清除其他 App 的项。覆盖升级保留标记和账号。
- 默认不申请 ATT、相册、相机、麦克风、定位等无关权限；实际引入跨应用跟踪或 H5 功能时再补对应授权、用途文案和数据声明。
- 收集第三方 SDK 隐私清单、Required Reason API 用途和隐私政策；PrivacyInfo.xcprivacy、App Store 隐私标签和实际行为要一致。随机标识仍可能属于应披露的用户/设备关联数据。

## 8. 一键登录落地

官方 iOS 入口已核对：TXCommonHandler、TXCustomModel；setAuthSDKInfo、getLoginTokenWithTimeout、cancelLoginVCAnimated。iOS 超时单位是秒，launch_total_timeout_ms=5000 映射为 5.0 秒；不额外启用 Android 已声明未实现的三项重试配置。

SDK 回调 600001 对应授权页已展示，600000 对应取得临时凭证，700000/700001 对应返回/其他登录；以锁定 SDK 版本头文件和 Demo 复核枚举。对 H5 只输出 0/3001/3003 等既有业务码，不能直接透传 SDK 码。

SDK 集成要求：

1. 从阿里云控制台取 iOS 包、Demo、版本记录和校验值，确认支持 device arm64、目标模拟器及最低 iOS。
2. 官方当前说明：静态方式包含 ATAuthSDK、YTXMonitor、YTXOperators；动态方式 ATAuthSDK_D；不能同时集成静态与动态主库。实际锁定包若结构不同，以该包官方说明验证并记录。
3. 静态库按官方规则链接、不 Embed；动态库按实际产物 Embed & Sign，避免同时嵌入重复组件；ATAuthSDK.bundle 必须包含。SDK Linker Flags、系统框架和隐私资源不能漏掉。
4. 新建 iOS 认证方案绑定 Bundle ID；不同游戏/环境的密钥映射受控管理，Android 的签名 MD5、AAR 和 scheme_secret 不能复用。
5. 所有 SDK 调用在同意隐私后进行；SDK 预取号同样属于认证网络行为。Release 关闭控制台敏感日志，并核对 SDK 埋点上传。
6. 必须做真 SIM、真实蜂窝数据环境验收；模拟器仅通过 Gateway 替身验证状态和 UI，不声称完成运营商取号。
7. 不覆盖、截获或伪造 SDK 号码、登录按钮及协议的必要认证能力；品牌头部通过 SDK 支持的自定义区域与布局回调实现。

iOS UI 的协议字号至少 12pt、号码至少 16pt、登录按钮至少 40pt 高且至少半个弹窗宽是官方当前限制；具体设计与过小配置回退详见 UI 规范。

## 9. 在线更新、渠道与版本管理

本次流程：原生弹窗 → 校验并打开 HTTPS 更新入口 → 返回时保留正常提示状态 → 实际新包启动后重新判断版本。弹窗保留黄色头图、绿色按钮、圆形关闭控件；不展示不存在的下载百分比和“立即安装”。具体签名模式、安装服务和分发适配另行处理。

渠道码已定：channelCode 默认 ""；HTTP Header 始终包含 Packagecode，Bridge 的 packageCode 与其一致。配置支持未来填写合法值，但本次不开发渠道分包、归因、签名后改包或渠道切换界面。修改已签名 Bundle 会破坏签名。

CFBundleShortVersionString 用 x.y.z；CFBundleVersion 每次构建递增。成品清单同时记录业务版本、构建号、SDK 版本、Git 提交与配置摘要，便于两端问题对应。

## 10. 后续发行参考（不属于本次开发验收）

用户已明确本次不考虑分发模式。以下只保留未来改走 App Store 等路线时的参考，不作为开工、功能完成或本次排期的前置条件：

| 项目 | 影响与设计动作 |
| --- | --- |
| H5 壳与多游戏 | 审核 4.2 要求足够功能与体验；4.2.6 涉及模板批量 App；若含远程小游戏目录，适用 4.7 的更多要求 |
| 原生 Bridge | 若落入 4.7，4.7.2 对向小游戏开放原生 API 有专门限制，需确认许可与能力范围；不能用“Android 已实现”证明 iOS 可直接开放全部能力 |
| 登录方式 | 仅自有手机号/短信不自动推出必须 Sign in with Apple；若 H5 加第三方社交登录，要核查 4.8 的等效登录要求和例外 |
| 账号删除 | 支持注册账号时提供应用内删除入口及服务端完成流程；“退出登录”不是删除账号 |
| 中国大陆发行 | 核对当前 App Store Connect 要求的备案、游戏版号/资质及主体材料；由发行方确认具体适用项 |
| 隐私与年龄 | SDK 数据采集、协议、年龄分级；小游戏模式还需目录、内容治理、年龄限制及逐项同意等 4.7 要求 |
| 审核可访问性 | 提供可用测试账号、非运营商专属的登录回退、审核说明、可达 HTTPS 服务；不隐藏审核后才开启的功能 |

本方案的功能完成不等于已完成发行资格核查或审核；本次按既定壳工程功能及隐私要求开发和测试，充值专项不纳入。

## 11. 测试与质量门槛

完整环境矩阵见配套手册。每个模块既要有规则测试，也要有真实 WKWebView / 系统行为测试。

必须将 Android 审查中的边界写入 iOS 回归基线：

- logout 后旧兑换不得保存 Token 或开游戏。
- open=0 的坏认证子配置不影响启动；缺 code 不成功。
- 非对象 Bridge 参数按原 id 回 1001；方法特定 data 类型不变。
- NaN/Infinity/非法窗口整包验证后再应用，不留下半更新状态。
- POST 之后的新 GET 可重试；POST 本身不重放。
- 视口、安全区、方向变化重算窗口与悬浮球。
- 持久化失败能观察到；版本清洗不产生成品覆盖。
- UI 尺寸规则只有一个明确来源；不把旧需求和后续视觉调整混用。
- 隐私撤回与重新同意、清理被中断、privacyAuto=false 与 revoked 冲突，以及兑换后台取消/Token 已提交两种排序均有确定结果。
- 退出、后台、WKWebView 进程终止、内存警告和 SDK 重入都释放资源并保护新会话。

首期完成定义：目标 iPhone 真机主流程、三种登录、H5 游戏地址加载与统一白名单导航拦截、更新入口交接、隐私撤回、前后台/锁屏、四窗口生命周期、存储重启、来源拒绝和基础签名安装/覆盖验证都有记录；不以完成充值专项、企业分发、商店上架或 TestFlight 为验收条件。

## 12. 分阶段交付与估算

以下按 1 名熟悉 iOS 的开发、1 名可配合的 H5/服务端开发及兼职测试估算工作日，允许部分并行。包含隐私撤回和通用 WebView 导航拦截；不包含充值专项、分发平台、账号审核、SDK 采购、证书等待或上架审核。

| 阶段 | 交付与退出条件 | 估算 |
| --- | --- | --- |
| S0 环境与技术验证 | Mac/Xcode/真机调试签名可用；官方 SDK 在设备可初始化；确认 H5 游戏地址、白名单、平台 Header 与更新入口；确认 UI 差异 | 2–4 天 |
| S1 可运行主流程 | 构建配置、初始化、隐私、H5 登录、游戏 WKWebView、基础 Bridge 与本地服务测试 | 4–6 天 |
| S2 完整业务 | Token/一键登录、会话隔离、存储/角色、福利/悬浮球/心跳、加载恢复与常亮；隐私撤回和统一白名单导航拦截 | 7–10 天 |
| S3 UI 与更新 | 原生授权和更新视觉、协议、异常态、安全区/横竖屏适配、更新入口返回 | 3–5 天 |
| S4 构建与验收 | 逐游戏配置构建、基础签名成品检查、设备安装与覆盖、设备矩阵及问题收敛 | 3–5 天 |

修订后合计约 19–30 个工作日，约 4–6 周；按 S0 环境与 SDK 验证结果复估。分发平台、远程多小游戏体系、iPad 或远程 Mac 出包平台另估。

## 13. 开发前决策表

| 编号 | 事项 | 已确认结论或建议默认 | 最晚影响节点 |
| --- | --- | --- | --- |
| D01 | 地区与分发范围 | 已确认中国国内；最新企业签名说明覆盖此前开发/Ad Hoc 选项；本次排除分发模式开发 | 已明确范围，不阻塞功能开发 |
| D02 | 首期单游戏 App 还是多小游戏平台 | 先一个游戏、一个 Bundle ID；保留模板复用 | S0 架构、Bridge |
| D03 | 现有 Mac、Apple 开发者主体和真机 | Apple Silicon Mac、组织账号、有 SIM 的 iPhone | S0 |
| D04 | 系统和设备范围 | iPhone，iOS 16+；iPad 后续单独适配 | S0 SDK/设备矩阵 |
| D05 | H5 业务边界 | 已确认忽略充值专项；原生只加载 H5 游戏地址，相关跳转遵守统一白名单拦截；社交登录和删除账号按既有 H5 能力盘点 | S0–S1 联调 |
| D06 | 服务端 iOS 身份与配置 | ios 枚举、随机安装码、独立密钥与 HTTPS 更新入口待联调；Packagecode 默认空且发送已确认 | S1 联调 |
| D07 | 阿里云 iOS SDK 包与认证方案 | 官方锁版本；先跑最小设备集成 | S0 |
| D08 | UI 尺寸歧义 | 更新沿用 Android 当前 0.7 缩放；授权遵守 iOS SDK 最小字号与可点区域 | S3 前确认视觉稿 |
| D09 | 渠道码 | 已确认默认空字符串；原生业务请求 Header 携带 Packagecode；Bridge packageCode 一致；本次不做渠道分包 | S1 请求契约 |
| D10 | 是否需要 Windows 操作员远程出包 | 首期 Mac 本地脚本；后续 Windows 提交任务到受控 Mac | S4 之后 |
| D11 | 隐私撤回 | 已确认需要；可信 H5 设置入口，新增 revokePrivacyPolicy；本机清理及恢复设计详见 §4.1.1，后续同步接口文档 | S1 H5/原生契约 |

开发者可自行决定文件内部拆分、Auto Layout 约束组织、测试替身实现及 CI 品牌；上述涉及发行、账号体系、数据和 UI 口径的事项不能靠代码静默决定。

## 14. 官方依据与核对日期

本节为 2026-11-02 实际读取的官方资料；Apple 的 Xcode 兼容和分发政策会变化，开工和上传前应再核对。

- [Apple Xcode 支持表](https://developer.apple.com/support/xcode/)：macOS、SDK、部署目标与设备调试支持是不同列。
- [Apple 即将实施及已生效要求](https://developer.apple.com/news/upcoming-requirements/)：当前页面写明自 2026-04-28 上传构建使用 Xcode 26+ 与 iOS 26 SDK+；不是要求最低运行 iOS 26。
- [Apple Developer Program 注册](https://developer.apple.com/programs/enroll/)：99 美元/会员年或当地价格；主体与组织注册要求。
- [TestFlight 概览](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview)：构建最长 90 天，100 名内部成员、最多 10000 名外部测试者；外测审核要求。
- [开发设备管理](https://developer.apple.com/help/account/devices/devices-overview)：每会员年按设备产品系列管理注册数量。
- [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)：2.5.2、3.1、4.2、4.2.6、4.7、4.8、5.1.1 等。
- [Apple 企业开发者计划](https://developer.apple.com/programs/enterprise/)：限符合条件的组织向内部员工分发；不作为普通玩家发行方式。
- [阿里云 iOS 客户端接入](https://help.aliyun.com/zh/pnvs/developer-reference/the-ios-client-access)：SDK 组件、API、回调、UI 布局和字号限制。
- [阿里云客户端准备](https://help.aliyun.com/zh/pnvs/developer-reference/the-preparatory-work-2/)：官方 SDK 和 Demo 的获取流程。
