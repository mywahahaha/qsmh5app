# iOS 游戏背景、闪图与打包器需求分析

日期：2026-11-03。本文保留开发前的需求与可行性分析口径；用户随后授权实施，当前实现与验证见[实施记录](iOS图片与打包器实施记录_ios-assets-packager-development.md)，操作见[打包器指南](../ios/Packager/使用指南_usage.md)。下文“当前缺口”和“建议”描述分析时状态，不替代实施结果。

## 一、结论与交付范围

可行。建议拆成三项相互配合的交付：

1. **iOS 图片展示能力**：静态系统启动画面、应用内本地闪图、初始化接口远程背景图及 H5 首帧过渡。
2. **多游戏资源与构建配置**：游戏 ID、名称、Bundle ID、版本、方向、图标、闪图清单和可选游戏地址统一生成，消除游戏 29 的固定配置。
3. **Mac 签名打包器**：通过原生界面选择游戏配置、图片、签名身份和描述文件，隔离构建、导出 IPA、校验最终产物并生成出包摘要。

Android 的业务规则和打包流程可参考；iOS 的启动画面、AppIcon、WKWebView、签名与分发需要独立实现。现有 iOS 签名脚本已经跑通过 Ad Hoc 出包，可作为后端基础，尚不具备通用多游戏打包器的完整能力。

建议默认延续当前 iPhone、iOS 16 及以上范围；首版以 PNG 图片和 Ad Hoc 导出为主。视频闪屏、动态广告、App Store/TestFlight 上传、OTA 下载站、云签名平台不作为首版必需项。

## 二、Android 已实现的实际行为

### 2.1 三种图片职责

| 类型 | 来源 | 展示时间与用途 |
| --- | --- | --- |
| 系统启动画面 | Android 系统启动主题 | Android 12 等系统启动阶段；当前隐藏默认图标、使用黑底，与应用内闪图衔接 |
| 应用内闪图 | 打包进应用的 PNG 资源 | 启动遮罩；首个可见 H5 就绪后按时序移除 |
| 游戏背景图 | 初始化响应 `data.bgImage` | H5 容器下方的远程底图，可贯穿登录、游戏和福利窗口 |

因此，“打包器选择闪图”与“接口下发背景图”是两条独立链路。更换远程背景无需重新打包；更换本地闪图和游戏图标需要重新构建并签名。

### 2.2 应用内闪图

依据 [SplashSelector.kt](../android/app/src/main/java/com/qishimeng/game/splash/SplashSelector.kt)、[StartupSplashView.kt](../android/app/src/main/java/com/qishimeng/game/splash/StartupSplashView.kt) 和 [MainActivity.kt](../android/app/src/main/java/com/qishimeng/game/MainActivity.kt)：

- 从 `qsm_splash_images` 数组读取资源及尺寸信息。资源名包含方向和宽高，例如 `splash_landscape_1280x768`。
- 优先选择与视口方向相同的图片；同方向无有效图片时，从全部有效资源回退选择。
- 比较 `abs(ln(图片宽高比 / 视口宽高比))`，选差值最小的图片；差值相同选择面积更大的图片。
- 黑色底、等比例铺满并裁切，展示期间拦截触摸；无有效图片则不显示。
- 从闪图视图挂载开始计时，正常路径最短 **1.2 秒**、最长 **5 秒**。
- 首个 H5 页面可见后通知结束闪图：可能是协议、登录或游戏页；不足 1.2 秒时等到最短时间，已超过则立即移除。
- 初始化解析/业务失败、网络错误提示或原生更新窗口需要展示时，提前移除闪图，错误交互优先。失败路径不保证显示满 1.2 秒。
- 5 秒上限只负责撤下遮罩，不代表初始化或游戏加载成功。下面继续显示正常加载或错误状态。
- 页面销毁时取消定时任务；普通重新初始化不会重新创建一次闪图。

### 2.3 初始化远程背景 `bgImage`

依据 [InitConfigParser.kt](../android/app/src/main/java/com/qishimeng/game/config/InitConfigParser.kt)、[StartupBackgroundLoader.kt](../android/app/src/main/java/com/qishimeng/game/web/StartupBackgroundLoader.kt) 和 [WebContainer.kt](../android/app/src/main/java/com/qishimeng/game/web/WebContainer.kt)：

- `data.bgImage` 为可选字符串，去空白后为空则不加载。
- 初始化成功后异步下载，按视口尺寸采样，避免原图直接占用大量内存；现实现通常先读尺寸、再发第二次请求解码。
- 支持 HTTP/HTTPS 及重定向，连接超时 10 秒、读取超时 15 秒。
- 图片全屏等比例铺满、无点击行为；下载失败不阻塞登录和游戏。
- 清理前景 H5 窗口时保留底图，悬浮球位于窗口上层。
- WebView 背景透明；**若 H5 自身 `html/body` 绘制不透明背景，原生底图仍会被遮住**。
- 当前回调检查 Activity 是否销毁，未形成完整的初始化代次校验；iOS 应利用现有初始化 epoch，避免旧请求晚到覆盖新图。

### 2.4 H5 可见时机

[WebPageView.kt](../android/app/src/main/java/com/qishimeng/game/web/WebPageView.kt) 最新逻辑在加载期间保持布局和绘制、隐藏页面视觉层；收到 `onPageCommitVisible` 后执行 **200ms 淡入**，再通知首个内容可见。`onPageFinished` 用于 Bridge 注入兜底，不再作为首帧可见依据；最终错误面板应能正常显示。

此时序与闪图结束有关，不能只做一张定时消失的图片而忽略 H5 的白屏、错误和重试状态。

## 三、Android 打包器的可复用设计

现有 [packager](../packager/) 使用 Python 标准库与 Tkinter，Windows 启动入口调用图形界面。

| 模块 | 当前行为 | iOS 可复用的设计 |
| --- | --- | --- |
| [models.py](../packager/models.py)、[gui.py](../packager/gui.py) | 游戏 ID、版本、名称、包名、签名、图标、闪图、方向、输出目录；后台任务和进度 | 每游戏配置、资源预览、任务状态 |
| [config_store.py](../packager/config_store.py) | JSON 保存/加载，不保存签名密码 | 配置可复用，密码不落盘 |
| [resource_mapper.py](../packager/resource_mapper.py) | 读取闪图目录直接文件，按文件名尺寸分类，去重、复制并生成资源数组 | 资源清单及确定性选择规则 |
| [build_service.py](../packager/build_service.py) | 临时复制工程、写资源、Gradle 构建、签名校验、输出 APK 与摘要 | 隔离构建、校验后发布、结果可追溯 |
| [validators.py](../packager/validators.py) | 输入与 PNG 头部校验 | 构建前统一检查，但 iOS 需要更完整的图片验证 |

需要区分文档目标与源码现状：

- 图标目前复制为 `mipmap-nodpi/ic_launcher.png`，不是完整的多密度图标生成器。
- 闪图按文件名的 `宽x高` 正则解析，不依赖“横板/横版”等文字；方图、非 PNG、缺尺寸命名会报错，同方向同尺寸取排序后的首个文件。
- PNG 校验主要读取头部和 IHDR，未完整解码，也未强制文件名尺寸与真实像素一致；iOS 工具应补强这些校验。
- Android 的 keystore、alias、证书 MD5 和 Gradle 流程不能直接替换扩展名用于 IPA。
- 当前 Android 产物同名可覆盖；iOS 建议每任务独立输出目录，避免新失败任务影响已有安装包。

## 四、iOS 当前缺口

| 检查项 | 当前情况 | 需要调整 |
| --- | --- | --- |
| 初始化模型 | [Models.swift](../ios/Core/Models.swift) 未解析 `bgImage` | 增加可选字段，空值兼容 |
| 根容器 | [RootViewController.swift](../ios/App/RootViewController.swift) 无远程底图层和闪图层 | 增加叠层、生命周期及非阻塞图片加载 |
| H5 透明链路 | [WebPageController.swift](../ios/Features/WebPageController.swift) 的 WKWebView 透明，但外层 UIView 使用系统背景色 | 同时处理外层、滚动背景及页面过渡，避免包裹层挡图 |
| 首帧过渡 | 页面创建即展示，现有 `didCommit/didFinish` 通知不能直接等同 Android 首帧可见 | 导航代次与渲染就绪适配、200ms 淡入及超时/错误验证 |
| 本地资源 | 尚无正式 AppIcon 资产目录、闪图清单 | 生成 Assets 和资源清单；建立静态 LaunchScreen |
| 游戏配置 | [Base.xcconfig](../ios/Config/Base.xcconfig) 固定游戏 29、Bundle ID、名称和版本 | 由每次构建配置生成 |
| 游戏地址 | [Info.plist](../ios/Config/Info.plist) 写死游戏 29 地址 | 默认使用初始化返回地址；每游戏可选 override，遵守现有可信来源策略 |
| 横竖屏 | 当前方向数组包含固定 `LandscapeRight` | 生成完整方向数组，避免只改变量后竖屏包仍混入横屏 |
| 签名导出 | [export_signed.py](../ios/Scripts/export_signed.py) 可使用现有钥匙串身份导出，但 Bundle ID 等固定 | 参数化配置、匹配身份、导出类型与期望值 |
| 归档检查 | [verify_archive.py](../ios/Scripts/verify_archive.py) 存在固定游戏/包名/方向校验 | 接受本次配置，并补充最终 IPA 验证 |

本地调试配置、替身场景和 [archive_unsigned.sh](../ios/Scripts/archive_unsigned.sh) 也应一起检查，避免 Release 改为多游戏后，Debug 或校验仍读取游戏 29。SDK、图标与闪图必须显式纳入临时工程；产物目录、证书目录不能被整目录带入应用。

## 五、iOS 图片功能需求建议

### 5.1 显示层次

从下至上：

```text
纯色兜底
  → 远程 bgImage：全屏 aspectFill，无交互
  → H5 窗口 / 加载及错误界面：交互遵守安全区域
  → 悬浮球
  → 应用内闪图：仅启动期间遮罩并拦截触摸
```

系统 LaunchScreen 是进入应用前由 iOS 控制的独立阶段。它应使用静态资源，与应用内闪图视觉衔接；联网、选图和 1.2–5 秒计时放在应用内实现，不能依赖系统启动画面的停留时间。[Apple 启动画面说明](https://developer.apple.com/documentation/xcode/specifying-your-apps-launch-screen)

### 5.2 本地闪图

- 首版接受 PNG，可选闪图目录；未选择或无有效资源时直接进入正常启动流程。
- 使用与 Android 一致的方向、宽高比和分辨率优先规则；按实际显示视口选择，黑底、等比例铺满，可预览裁切效果。
- 正常路径最短 1.2 秒、最长 5 秒；首个协议、登录或游戏 H5 就绪后结束等待。
- 错误、协议等原生必要交互、SDK 授权或更新提示出现前撤下遮罩，确保用户能看到和操作。
- 超时、退出、初始化失败和控制器销毁均能取消任务；同一次启动中的前后台切换、福利页开关、退出登录不重播。
- 资源清单为构建生成文件，不在运行时扫描任意本机目录。不得把包含证书的整个资料目录复制进 Bundle。

### 5.3 远程背景

- `bgImage` 为空、URL 无效、下载/解码失败，均回退纯色，不阻塞其他业务。
- URLSession 异步下载，尽量单次获取；设置超时、下载字节上限、像素上限，并通过 ImageIO 按视口降采样。
- 采用现有 HTTPS 安全策略；不为兼容 Android 的 HTTP 地址全局放开 ATS。若业务确需 HTTP，单独评估限定域名配置。
- 图片请求不携带业务 Token 或 Cookie；重定向后的地址也受策略检查。
- 请求绑定初始化 epoch、URL 和任务标识；重新初始化时取消旧请求并清理旧状态，迟到回调不可覆盖当前会话。
- 同一游戏的正常窗口切换、退出登录可保留当前有效背景；隐私撤回取消任务、释放图片并清理该功能产生的缓存。首版无需引入持久图片缓存。
- 背景延伸到屏幕边缘；按钮、错误操作和悬浮球继续遵守当前安全区域布局。
- H5 若需要透出底图，应保证对应页面背景透明。iOS 不强制注入全站透明 CSS，避免改变游戏原有绘制。

### 5.4 首帧与 H5 兼容性

业务字段继续使用 `data.bgImage`，无需新增 H5 业务 Bridge 方法。H5 唯一可能需要的共同调整是页面背景透明；这也是 Android 上底图可见的条件。

Apple 的 `didCommit` 表示主框架开始接收内容，不能直接视为 Android `onPageCommitVisible` 的同等信号。[Apple 导航回调说明](<https://developer.apple.com/documentation/webkit/wknavigationdelegate/webview(_:didcommit:)>)

实现时应将导航代次、文档就绪及可取消的原生渲染就绪通知结合，`didFinish` 作为兜底，并在目标系统上通过延迟 H5、截图或录屏验证。JavaScript 下一帧回调本身不能证明 GPU 已完成合成，也不能无限等待游戏全部资源下载。

加载期间保持 WebView 布局及绘制，避免隐藏方式导致永远无法产生首帧。可见时按 Android 目标执行 200ms 淡入；切页、重试、错误、5 秒闪图上限及触摸拦截分别验收。[Apple 页面底色说明](https://developer.apple.com/documentation/webkit/wkwebview/underpagebackgroundcolor)

## 六、Mac 打包器需求建议

### 6.1 使用方式与技术选择

建议放在 `ios/Packager/`，采用 **SwiftUI 原生界面 + 可独立运行的构建后端/CLI**。首版可泛化现有 Python 标准库脚本，复用已验证的归档、导出与校验流程；后续再按分发需要封装 Python 运行时，或逐步迁移为全 Swift。

本机当前 Python 3.12 无 Tkinter，不能直接运行 Android GUI。SwiftUI 适合本机原生文件选择、图片预览和安全输入；全 Swift 可减少 Python 依赖，但需要重写更多后端能力。打包器即使封装为 `.app`，实际编译仍依赖完整 Xcode 和 iOS SDK，首版不承诺脱离 Xcode 出包。

运营操作流程：填写或加载游戏配置 → 选择图标/闪图 → 选择签名身份与描述文件 → 预检 → 开始构建 → 查看进度 → 获取已验证的签名 IPA 和摘要。首版建议串行执行构建任务，支持取消及失败定位。

### 6.2 表单与配置

| 字段 | 要求 |
| --- | --- |
| 游戏 ID | 必填，正整数；不能据此自动推导 Bundle ID |
| 游戏名称 | 必填，作为桌面显示名称 |
| Bundle ID | 必填，合法格式且符合所选描述文件授权范围 |
| 版本 / 构建号 | 分别填写 `CFBundleShortVersionString` 与 `CFBundleVersion`，按平台规则校验 |
| 游戏方向 | 横屏/竖屏，生成完整支持方向集合 |
| 游戏图标 | 必选；建议首版要求 1024×1024、不透明、正方形 PNG |
| 闪图目录 | 可选；直接文件、PNG、尺寸命名规则与预览说明明确 |
| 游戏地址覆盖 | 可选，默认使用初始化返回的游戏地址；不得继承上一游戏地址 |
| 签名身份 | 优先选择钥匙串已有有效身份，也支持导入 `.p12/.pfx` |
| 导入密码 | 安全输入，仅内存使用，不写配置、命令行或日志 |
| 描述文件 | 选择 `.mobileprovision`；自动展示 Team、类型、有效期、授权范围及设备数量 |
| 输出目录 | 必填，预检可写、可用空间以及产物冲突 |

Team 和分发类型由真实描述文件推导，不靠手填名称改变权限。Android 的 alias、证书 MD5 不属于 iOS 表单必填项。阿里云号码认证需按 iOS Bundle ID 配置，不能沿用其他包名方案；工具预检通过不代表真实取号通过。

配置 JSON 可保存非敏感字段与本机路径，加载后重新检查文件存在性与有效期；密码不保存。默认母包渠道码为空，继续兼容已有 `packCode/子目录名` 读取。若提供可选渠道码输入，必须在最终签名前写入；自动批量分包可独立排期。

### 6.3 图片校验与生成

- 图标和闪图通过 ImageIO 完整解码，不能只看扩展名/PNG 头；验证像素、透明度及资源大小。
- 闪图文件名尺寸必须与真实尺寸一致；不一致应提示修正，不能默默按错误尺寸选图。
- 同方向同尺寸重复素材按确定性规则处理并展示提示，预览实际选中项和裁切区域。
- 为 AppIcon 生成适配 Xcode 16.4 的资产目录及 Contents.json，或采用该版本实测通过的单张 1024 资产配置；不依赖新版 Icon Composer。[Apple 图标配置说明](https://developer.apple.com/documentation/xcode/configuring-your-app-icon)
- 闪图生成资源清单，应用只读取清单内资源。系统 LaunchScreen 引用明确的静态资源，不在启动阶段执行联网选图。

### 6.4 签名与安全输入

`.cer` 只含公钥证书，不能单独完成签名；可用签名身份必须有匹配私钥。`.p12/.pfx` 可提供证书和私钥，描述文件仍需单独匹配。[Apple 签名证书说明](https://developer.apple.com/documentation/technotes/tn3161-inside-code-signing-certificates)

预检至少覆盖证书/私钥可用性、有效期、Team、Bundle ID、描述文件内证书、分发类型及 entitlements。通配符描述文件不等于任意 Bundle ID 均可使用；特殊能力可能需要独立 App ID 与相应授权。

优先复用现有钥匙串身份，减少导入。导入功能采用原生 Security API 并明确导入目标和生命周期：macOS 的 `SecPKCS12Import` 默认可能写入默认钥匙串，不能将其描述为无副作用的只读解析。临时钥匙串与 codesign 访问权限需要专项原型验证，不自动更改全局钥匙串设置，不误删已有身份；允许系统安全提示。[Apple 身份导入说明](https://developer.apple.com/documentation/security/importing-an-identity)

### 6.5 构建、导出和最终验证

1. 检查 Xcode、SDK、Python 后端、资源、配置与签名材料。
2. 创建任务工作区及独立 DerivedData，复制所需 iOS 源码、SDK 和资源；排除旧 artifacts、证书资料及打包器自身。
3. 生成本次游戏配置、方向数组、AppIcon、LaunchScreen 资源与闪图清单；不改用户工程的固定配置。
4. 执行 `xcodebuild archive` 和 `-exportArchive`，按本机 Xcode 支持的方法导出。[Apple 命令行归档与导出说明](https://developer.apple.com/library/archive/technotes/tn2339/_index.html)
5. 解包**最终导出的 IPA**，校验签名与嵌套代码、身份、embedded profile、entitlements、Bundle ID、版本、游戏 ID、方向、图标和闪图资源；归档成功不能替代最终 IPA 验证。
6. 核对游戏 URL 策略、渠道资源及 SDK 依赖，确认没有证书、密码和临时配置泄漏进入 Bundle。
7. 生成成功摘要后原子发布到唯一输出目录；取消或失败时清理临时任务，不覆盖已有好包。

签名后修改图标、Info.plist 或闪图再直接压缩成 IPA，会破坏签名。所有资源写入必须在最终签名前完成；最终签名校验也不能替代设备安装验收。[Apple 描述文件说明](https://developer.apple.com/documentation/technotes/tn3125-inside-code-signing-provisioning-profiles)

### 6.6 产物

建议目录名包含 `游戏ID_BundleID_版本_构建号_签名类型_时间`，输出：

- 签名 IPA、xcarchive 与 dSYM。
- 脱敏构建日志和中文出包摘要。
- JSON 配置摘要、图片资源哈希、IPA SHA-256。
- 描述文件名称、有效期、分发类型和设备数量；不列设备 UDID，不含密码/私钥。
- 对应分发方式的安装说明。

## 七、本机与素材检查结果

以下为 2026-11-03 的只读检查，不代表新增功能已构建或验收：

| 项目 | 检查结果与边界 |
| --- | --- |
| 系统 | Intel x86_64、macOS 15.5 |
| Xcode | 16.4（16F6），完整工具链已具备 |
| Python | 3.12，当前解释器未安装 Tkinter |
| 现有签名链路 | 历史构建 3 Ad Hoc IPA 已导出及验签；本轮未重新打包 |
| 当前描述文件 | `h5App.mobileprovision`，Team `7BNPHDL54G`，授权 `com.un74meng.*`，到期 UTC 2027-11-02 08:15:15 |
| 当前签名身份 | 钥匙串中存在 1 个与当前描述文件证书匹配的有效身份 |
| 分发范围 | 当前为 Ad Hoc，登记 **7 台设备**，不是企业分发；任意 iPhone 不能直接安装 |
| 旧包区别 | 上轮构建 3 内嵌描述文件登记 6 台；磁盘文件新增设备不会自动更新旧 IPA，需重新出包 |
| 阿里云 SDK | 本机有安装记录，仍需按各游戏 Bundle ID 配置与联调 |
| 闪图素材 | `refs/打包资料/证书/闪图/` 有 10 个 PNG，真实尺寸与文件名一致；去重后 9 种尺寸，5 横、4 竖 |

首个样本可继续采用游戏 29、`com.un74meng.yjhcmc`、横屏。其他游戏必须提供各自配置，不能仅替换游戏 ID 而保留此游戏的固定地址。

Ad Hoc 需要登记目标设备并由相应描述文件授权；当前无需再次连接 iPhone，后续安装由用户完成。[Apple Ad Hoc 要求](https://developer.apple.com/help/account/provisioning-profiles/create-an-ad-hoc-provisioning-profile)

## 八、实施顺序与验收

| 阶段 | 交付 | 主要验收 |
| --- | --- | --- |
| 1. 需求与规则整理 | 本文、字段及默认范围 | 明确系统启动画面/本地闪图/远程底图的来源和边界 |
| 2. 图片运行能力 | `bgImage`、透明容器、闪图选择/时序、首帧淡入 | 模拟器及替身页覆盖快/慢/失败/重试/初始化切换，不遮挡错误和必要原生交互 |
| 3. 通用构建后端 | 参数化工程配置、资源生成、CLI、签名导出和最终 IPA 校验 | 至少两套游戏配置交叉构建，检查 ID、名称、URL、图标、方向、签名互不串用 |
| 4. Mac 图形打包器 | 表单、预览、配置保存、进度取消、身份选择及导入 | 不手改 Xcode 工程即可完成一次出包；密码不落盘，失败不覆盖成功产物 |
| 5. 交付验收 | 样本游戏签名 IPA、摘要和说明 | 用户设备安装、真实游戏加载及所需 SDK 功能验收 |

图片测试至少包括：空值/无图、方向回退、比例相同选大图、损坏和超大图、1.2 秒下限、5 秒上限、错误提前撤罩、慢图迟到不覆盖、福利页切换及前后台不重播、H5 透明/不透明差异、横竖屏安全区域和裁切。

打包测试至少包括：不匹配证书/profile/Bundle、证书过期、缺私钥、图标透明/尺寸错误、闪图重复/尺寸命名不符、路径含空格与中文、取消/构建失败清理、重复出包和最终 IPA 签名/配置核对。第二个真实游戏材料未提供时，可以合成配置验证编译与隔离，但不能计为第二个线上游戏验收通过。

建议先完成图片能力与通用 CLI，再做 GUI；这样可先验证资源、签名和产物的完整链路，界面直接调用已验证的后端。

## 九、后续开发所需材料

每个游戏需整理：游戏 ID、Bundle ID、桌面名称、版本/构建号、方向、1024 图标、可选闪图目录、是否需要覆盖初始化游戏地址，以及匹配的签名身份和描述文件。现有游戏 29 与当前钥匙串身份可作为首个开发样本；无需在分析阶段重新提供密码。

正式验收应分开记录：模拟器/替身验证、最终 IPA 验签、用户设备安装、真实 H5 与阿里云取号。前一项成功不能替代后一项。
