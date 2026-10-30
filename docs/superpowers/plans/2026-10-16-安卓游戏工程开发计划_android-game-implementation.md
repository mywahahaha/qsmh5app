# 安卓 H5 游戏工程开发计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务执行。步骤使用复选框跟踪。

**Goal:** 从零交付可复用的 Android H5 游戏壳工程、Windows GUI 母包构建工具和渠道分包联调流程，使多个游戏可使用独立参数与签名出包。

**Architecture:** Android 原生端采用 Kotlin + Android Gradle 工程，按 SDK Core、Network、Auth、Web Container、JSBridge、Update、Floating Ball 等职责分层；Web 内容使用设备当前 Android System WebView。Windows GUI 采用 Python + Tkinter 收集每款游戏的构建参数并调用同一 Android Gradle 模板，已签名 APK 再交给现有渠道分包脚本处理。

**Tech Stack:** Kotlin、Android Gradle Plugin/Gradle Wrapper、AndroidX、Android System WebView、阿里云号码认证 Android SDK v2.14.24（仓库 AAR）、Python 3、Tkinter、现有 `tools/channel_apk.py`。

**Spec:** `安卓H5游戏包开发需求文档_requirements.md`、`JSBridge方法清单_jsbridge.md`、`安卓渠道分包设计文档_android_channel_packaging.md`、`文档导航_documentation.md`。

## 全局约束

- 主工程、共用 Android 模板和 Windows GUI 都是本项目的正式交付物；仓库中的分包脚本、Vue 示例和阿里云 SDK 仅作为辅助参考。
- 初始化地址固定为 `https://box.74meng.com/h5_app/init`；初始化、心跳、一键登录兑换、本地 Token 登录请求按需求发送六个 Header；隐私同意前 `Devicecode` 与 `Accountoken` 发送空字符串。
- `jumpDomain` 只校验跳转到外部浏览器的 HTTP/HTTPS 主机名，精确匹配，不用于 Bridge 来源校验。
- 登录顺序为隐私流程、本地 Token 登录、配置允许时阿里云一键登录、H5 登录；网络异常不得清除 Token，只有服务端明确判定 Token 无效/过期时才清除。
- 设备标识仅在隐私同意后获取；优先 OAID、再 `ANDROID_ID`、最后生成并加密持久化 DIY 码，已生成 DIY 码不得被后来可用的真实标识替换。
- JSBridge 全部按 `JSBridge方法清单_jsbridge.md` 的 `QSMH5APP` 命名与 `Result` 回调约定实现；`changeGameUrl` 依需求对游戏初始主机精确匹配。
- 最低 Android 版本按需求建议 API 23；Web 内容使用系统当前稳定 Android System WebView，不打包固定 Chromium 内核。
- 图标输入为 PNG；图标、闪图目录由 GUI 选择，其余出包参数由 GUI 表单填写；游戏配置可保存但签名密码不得写入配置、日志或源码。
- 在线更新按 2026-10-28 新契约先判 `open`；`open=0` 忽略其他更新字段；任何更新异常都提示并放行后续登录，即使 `force=1` 也不得阻塞异常容错。下方 Task 7 保留历史方案，新实现以《原生版本更新实施计划》为准。
- 渠道分包以已签名 APK 为输入，不重签名；沿用现有 `tools/channel_apk.py` 并保留其行为。
- 代码与新文档使用中文约定；新增 Markdown 文件名遵守“中文标题_english.md”。

## Review Focus

- 初始化响应缺字段、类型错误、非 `code=0` 或网络失败：不得崩溃或误启动不安全流程；在初始化解析与启动状态机测试覆盖。
- Token 登录网络失败、普通业务失败与明确 Token 失效：前两者保留 Token 并回退，只有明确失效清除；在 Auth 测试覆盖。
- 隐私未同意时设备标识读取、Header 注入和 Bridge 回传：三处均为空且不得读取标识；在 Privacy/Network 集成测试覆盖。
- 心跳中动态弹窗重复实例、清单缺失/为空、保留实例及多窗口层级：必须遵守差异规则；在弹窗协调器测试覆盖。
- GUI 输入非法包名、缺失资源/证书、构建失败或输出覆盖：给出可理解错误且不泄露密码、不损坏已有产物；在打包器与 GUI 测试覆盖。

---

## 交付结构与边界

仓库目前没有 Android 主工程；计划从根目录新增以下结构。具体类可以在任务实施时按职责拆分，但不得把业务逻辑全部堆进 Activity 或 GUI 窗口类。

| 路径 | 职责 |
| --- | --- |
| `android/settings.gradle.kts`、`android/build.gradle.kts`、`android/gradle/libs.versions.toml`、`android/gradle/wrapper/` | Gradle 工程、依赖版本和可复现构建入口 |
| `android/app/build.gradle.kts`、`android/app/src/main/AndroidManifest.xml` | Android 应用构建、权限、网络安全和可配置构建字段 |
| `android/app/src/main/java/com/qishimeng/game/config/` | 构建常量、初始化配置模型和严格解析/降级策略 |
| `android/app/src/main/java/com/qishimeng/game/core/` | 启动状态机、生命周期协调、线程调度 |
| `android/app/src/main/java/com/qishimeng/game/network/` | HTTP 客户端、固定 Header 注入、接口请求和错误分类 |
| `android/app/src/main/java/com/qishimeng/game/privacy/` | 隐私同意持久化、设备标识策略和加密存储 |
| `android/app/src/main/java/com/qishimeng/game/auth/` | Token 会话、Token 自动登录、阿里云认证及登录回退 |
| `android/app/src/main/java/com/qishimeng/game/web/` | WebView 实例注册、页面生命周期、窗口尺寸/层级、外链策略 |
| `android/app/src/main/java/com/qishimeng/game/bridge/` | `QSMH5APP` Bridge 注册、参数校验、异步回调与方法实现 |
| `android/app/src/main/java/com/qishimeng/game/update/` | 更新字段校验、下载/安装流程及异常放行 |
| `android/app/src/main/java/com/qishimeng/game/floatball/` | 悬浮球显示、激活图标、定位、拖动及福利大厅交互 |
| `android/app/src/main/java/com/qishimeng/game/role/` | 角色数据本地存储、合并和联合索引查询 |
| `android/app/src/main/res/` | 默认界面和资源；游戏资源由 GUI 构建时注入 |
| `android/app/src/test/`、`android/app/src/androidTest/` | 单元与 Android 集成测试 |
| `packager/gui.py`、`packager/build_service.py`、`packager/config_store.py`、`packager/resource_mapper.py`、`packager/validators.py` | Tkinter 界面、Gradle 调用、无密码配置保存、资源映射及输入校验 |
| `packager/tests/` | 参数校验、资源映射、命令构建和 GUI 服务单元测试 |
| `tools/channel_apk.py` | 已有渠道 APK 写入工具；原则上不改动，仅补充其测试/使用联调 |
| `docs/` | 更新架构/出包/联调文档和待服务端确认事项 |

## 共享接口约定

- `GameConfig`: `gameId: Int`、`gameVersion: String`、`applicationName: String`、`applicationId: String`、图标 PNG、闪图输入目录、签名文件与 alias/密码；签名密码仅在构建进程内存中传递。
- `GameInfo`: `packageCode: String`、`gameId: Int`、`gameVersion: String`、`accountToken: String`、`deviceType: String`（固定 `android`）、`deviceCode: String`。
- `ApiResult<T>`: `code: Int`、`msg: String`、`data: T?`；Bridge 外层统一使用 `Result(code, msg, data)`，细节沿 Bridge 文档。
- `WindowSpec`: `instance: String`、`url: String`、`width/height/left/top`；动态心跳弹窗使用既有实例则忽略 URL 和尺寸变化，清单为空关闭全部受管理窗口，四个保留实例不受同步清理影响。
- 出包脚本接受 GUI 已验证的配置，调用 Gradle Wrapper 构建已签名 APK，返回结构化状态、日志摘要和 APK 绝对路径；任何错误都不得输出签名密码。

## 实施计划

### 阶段一：Android 工程基座与可复现构建

### Task 1：建立可运行的 Android Gradle 壳工程

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle/libs.versions.toml`
- Create: `android/gradle/wrapper/gradle-wrapper.properties`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/com/qishimeng/game/MainActivity.kt`
- Create: `android/app/src/main/res/values/strings.xml`
- Test: `android/app/src/test/java/com/qishimeng/game/BuildConfigTest.kt`

**Interfaces:**
- Produces: 可通过 Gradle Wrapper 构建、安装并启动的单 APK Android 壳；最低 `minSdk=23`；采用 Kotlin 和 Android System WebView，不把游戏 ID、包名或签名写死在工程默认值中。

- [ ] **Step 1: 建立工程与构建配置测试**，断言应用模块、`minSdk=23`、Gradle Wrapper 存在，构建字段可被 Gradle 属性注入。
- [ ] **Step 2: 运行失败测试**，执行 `cd android; .\gradlew.bat testDebugUnitTest`；预期工程/测试尚不存在或配置断言失败。
- [ ] **Step 3: 创建最小工程和启动 Activity**，锁定相互兼容的 JDK、Android Gradle Plugin、Gradle、Kotlin 版本并记录在版本目录；配置 `applicationId`、`gameId`、`gameVersion` 为构建参数，提供可启动占位界面。
- [ ] **Step 4: 验证构建和启动**，运行 `cd android; .\gradlew.bat testDebugUnitTest assembleDebug`；预期测试通过并生成可安装 Debug APK。

### Task 2：定义配置模型、初始化解析和启动状态机

**Files:**
- Create: `android/app/src/main/java/com/qishimeng/game/config/InitConfig.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/config/InitConfigParser.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/core/StartupState.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/core/StartupCoordinator.kt`
- Test: `android/app/src/test/java/com/qishimeng/game/config/InitConfigParserTest.kt`
- Test: `android/app/src/test/java/com/qishimeng/game/core/StartupCoordinatorTest.kt`

**Interfaces:**
- Consumes: Task 1 的应用入口与构建常量。
- Produces: `InitConfigParser.parse(json: String): ParseResult<InitConfig>`；`StartupCoordinator.start(): Flow<StartupState>`。启动步骤顺序由隐私、更新、Token 登录、阿里云登录、H5 登录、游戏页状态表示。

- [ ] **Step 1: 编写解析与状态转换测试**：验证初始化样例可解析、错误类型/缺字段可报告；初始化失败不能进入游戏；状态顺序满足需求。
- [ ] **Step 2: 运行目标测试确认失败**，执行 `cd android; .\gradlew.bat testDebugUnitTest --tests '*InitConfigParserTest' --tests '*StartupCoordinatorTest'`。
- [ ] **Step 3: 实现最小配置数据类和启动状态机**；将必需/可选字段区分，未知字段忽略，类型错误返回可诊断结果，不在解析层静默造出可执行更新配置。
- [ ] **Step 4: 运行目标测试确认通过**，并用需求文档中的初始化 JSON fixture 验证解析。

### Task 3：实现网络层与固定 Header 注入

**Files:**
- Create: `android/app/src/main/java/com/qishimeng/game/network/ApiClient.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/network/GameHeaders.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/network/ApiModels.kt`
- Test: `android/app/src/test/java/com/qishimeng/game/network/GameHeadersTest.kt`
- Test: `android/app/src/test/java/com/qishimeng/game/network/ApiClientTest.kt`

**Interfaces:**
- Produces: `GameHeaders.toMap(info: GameInfo, privacyAgreed: Boolean): Map<String,String>`；请求均显式带 `Gameid`、`Devicecode`、`Packagecode`、`Devicetype`、`Accountoken`、`Gameversion` 六项；初始化 URL 固定为 `https://box.74meng.com/h5_app/init`。
- 请求范围：初始化、`popupInterval.url` 心跳、`aliyunOnekeyConfig.config.exchange_url` 兑换、本地 Token `POST /h5_app/login_by_token`。

- [ ] **Step 1: 写 Header 与 API 请求测试**：覆盖空值、正常值、隐私未同意时 `Devicecode`/`Accountoken` 为空、URL/方法和 JSON 解析。
- [ ] **Step 2: 运行目标测试确认失败**，执行 `cd android; .\gradlew.bat testDebugUnitTest --tests '*GameHeadersTest' --tests '*ApiClientTest'`。
- [ ] **Step 3: 实现统一网络客户端**；接口超时/重试可配置；不得记录 token、`scheme_secret` 或 auth_token；只在约定四类请求注入上述固定 Header。
- [ ] **Step 4: 运行目标测试确认通过**；用 MockWebServer 验证实际发出的 Header 与请求路径。

### 阶段二：隐私、登录与数据能力

### Task 4：实现隐私同意流程与设备码安全存储

**Files:**
- Create: `android/app/src/main/java/com/qishimeng/game/privacy/PrivacyConsentStore.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/privacy/DeviceCodeProvider.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/privacy/DeviceCodeStore.kt`
- Test: `android/app/src/test/java/com/qishimeng/game/privacy/PrivacyConsentStoreTest.kt`
- Test: `android/app/src/test/java/com/qishimeng/game/privacy/DeviceCodeProviderTest.kt`
- Test: `android/app/src/androidTest/java/com/qishimeng/game/privacy/DeviceCodeStoreTest.kt`

**Interfaces:**
- Produces: 同意状态存取接口、同意后获取设备码接口；未同意时不调用 OAID/`ANDROID_ID` 提供器，`deviceCode` 始终为空。

- [ ] **Step 1: 写隐私与设备码策略测试**：`privacyAuto=true` 首次启动等待同意；false 保存同意；已有同意忽略配置；拒绝撤销同意；OAID/ANDROID_ID/D.I.Y. 三种优先级及大小写；DIY 生成后重启仍不替换。
- [ ] **Step 2: 运行测试确认失败**，执行 `cd android; .\gradlew.bat testDebugUnitTest --tests '*PrivacyConsentStoreTest' --tests '*DeviceCodeProviderTest'`。
- [ ] **Step 3: 实现隐私持久化与设备码提供器**；输入 UTF-8 后 MD5 输出 32 位十六进制；OAID/ANDROID_ID 结果大写、DIY 结果小写；DIY 源码加密保存于应用私有存储；对 SDK/系统标识读取只在 consent gate 后执行。
- [ ] **Step 4: 运行单测及 Android 存储集成测试**；验证无同意态下 Bridge 和 Header 测试都观察到空值。

### Task 5：实现 Token 会话和本地 Token 自动登录

**Files:**
- Create: `android/app/src/main/java/com/qishimeng/game/auth/AccountTokenStore.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/auth/TokenLoginService.kt`
- Modify: `android/app/src/main/java/com/qishimeng/game/core/StartupCoordinator.kt`
- Test: `android/app/src/test/java/com/qishimeng/game/auth/TokenLoginServiceTest.kt`

**Interfaces:**
- Consumes: Task 3 `ApiClient`、Task 4 隐私同意状态。
- Produces: `TokenLoginService.loginSavedToken(): TokenLoginOutcome`；统一成功响应 `code=0`、`data.token`，仅服务端明确 Token 无效/过期错误码清除 Token，其余失败回退且保留。

- [ ] **Step 1: 写 Token 自动登录行为测试**：隐私未同意跳过；无 Token 跳过；成功更新 Token 并进入游戏；网络错误/非失效业务错误保留 Token 并继续；明确失效清除后继续登录。
- [ ] **Step 2: 运行测试确认失败**，执行 `cd android; .\gradlew.bat testDebugUnitTest --tests '*TokenLoginServiceTest'`。
- [ ] **Step 3: 实现加密 Token 存储和 `/h5_app/login_by_token` 调用**；登录请求将本地 Token 放 `Accountoken` Header；不在 URL、日志或错误提示中暴露凭证。
- [ ] **Step 4: 运行测试确认通过**，并验证登录分支进入后续阿里云/H5 流程而非阻塞。

### Task 6：集成阿里云号码认证并完成登录回退

**Files:**
- Create: `android/app/src/main/java/com/qishimeng/game/auth/OnekeyLoginService.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/auth/LoginCoordinator.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/auth/AliyunAuthAdapter.kt`
- Create: `android/app/src/test/java/com/qishimeng/game/auth/LoginCoordinatorTest.kt`
- Create: `android/app/src/test/java/com/qishimeng/game/auth/OnekeyLoginServiceTest.kt`
- Create: `android/app/libs/auth_number_product-2.14.24-log-online-standard-cuum-release.aar`
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: Task 3 API 客户端、Task 5 Token 服务、Task 2 启动状态。
- Produces: `LoginCoordinator.login(): Flow<LoginState>`；阿里云 adapter 将初始化配置传入 SDK，取得 `auth_token` 后 POST 到配置 `exchange_url`；成功 token 保存并打开游戏，其他失败回落 H5 登录。

- [ ] **Step 1: 编写登录协调器与 SDK adapter fake 测试**：Token 优先；`open=0` 跳过阿里云；`open=1` 成功兑换继续游戏；取消、SDK/服务端失败均进入 H5；H5 的 `notifyLoginSuccess` 保存 token。
- [ ] **Step 2: 运行测试确认失败**，执行 `cd android; .\gradlew.bat testDebugUnitTest --tests '*LoginCoordinatorTest' --tests '*OnekeyLoginServiceTest'`。
- [ ] **Step 3: 从仓库压缩包提取指定 AAR**，按照随包集成说明和官方 API 完成初始化、授权、取消/失败回调、横竖屏弹窗配置及透明背景；`scheme_secret` 不写入日志；打开/关闭认证与 H5 页面淡入淡出，建议 200ms。
- [ ] **Step 4: 运行单测、构建及设备 SDK 冒烟验证**，确保阿里云 SDK 缺配置或不可用仍能进入 H5 登录页。

### Task 7：实现在线更新校验与容错放行

> 2026-10-28 修订：本任务的旧字段与“排除外部浏览器”规则已被新需求取代，后续实施以 [原生版本更新实施计划](原生版本更新实施计划_native-update.md) 和需求文档第 8 节为准：`open` 控制开关，`force` 控制关闭，`popup=1` 外部浏览器打开 `url`，`popup=0` 内部下载及系统安装。

**Files:**
- Create: `android/app/src/main/java/com/qishimeng/game/update/UpdatePolicy.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/update/UpdateService.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/update/UpdateDialog.kt`
- Test: `android/app/src/test/java/com/qishimeng/game/update/UpdatePolicyTest.kt`
- Test: `android/app/src/test/java/com/qishimeng/game/update/UpdateServiceTest.kt`

**Interfaces:**
- Consumes: Task 2 `InitConfig.updateInfo`、Task 3 网络模块。
- Produces: `UpdatePolicy.evaluate(info: UpdateInfo?): UpdateDecision`；`isUpdate=0` 直接继续；`isUpdate=1` 校验必要字段并执行可关闭/强制更新 UI；错误统一结束更新并继续登录。

- [ ] **Step 1: 写更新决策测试**：`isUpdate=0` 时其余字段即使错误也不访问；`isUpdate=1` 校验字段/类型/HTTP(S) 下载地址；强制与非强制正常弹窗规则；下载失败、字段异常均放行。
- [ ] **Step 2: 运行测试确认失败**，执行 `cd android; .\gradlew.bat testDebugUnitTest --tests '*UpdatePolicyTest' --tests '*UpdateServiceTest'`。
- [ ] **Step 3: 实现更新策略、下载进度、系统安装唤起和异常提示**；不实现需求明确排除的外置浏览器安装。
- [ ] **Step 4: 运行测试及下载/安装设备测试**，验证强制异常也不会阻塞登录状态机。

### 阶段三：Web 容器、JSBridge 与游戏运行态

### Task 8：构建 WebView 页面容器和安全路由

**Files:**
- Create: `android/app/src/main/java/com/qishimeng/game/web/WebInstance.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/web/WebContainer.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/web/WindowLayout.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/web/UrlPolicy.kt`
- Test: `android/app/src/test/java/com/qishimeng/game/web/UrlPolicyTest.kt`
- Test: `android/app/src/test/java/com/qishimeng/game/web/WindowLayoutTest.kt`
- Test: `android/app/src/androidTest/java/com/qishimeng/game/web/WebContainerTest.kt`

**Interfaces:**
- Consumes: Task 2 `h5Pages` 配置。
- Produces: 按 `instance` 注册、显示、置顶、关闭的 WebView 窗口容器；URL 策略分别提供页面加载、`changeGameUrl` 和外部浏览器跳转判断；`jumpDomain` 只用于外部浏览器目标验证。

- [ ] **Step 1: 写 URL、尺寸与容器测试**：HTTP(S) 校验；外链 `jumpDomain` 精确主机匹配、同意 HTTP/HTTPS、拒绝非 HTTP(S) 和未列入主机；`changeGameUrl` 与 game 初始主机精确匹配；百分比/数字/center 定位换算；实例排序置顶。
- [ ] **Step 2: 运行测试确认失败**，执行 `cd android; .\gradlew.bat testDebugUnitTest --tests '*UrlPolicyTest' --tests '*WindowLayoutTest'`。
- [ ] **Step 3: 实现 WebView 容器与生命周期**：独立 `web_login`、`web_agreement`、`web_welfare`、`web_game`；支持淡入淡出；Bridge 来源校验独立于 `jumpDomain`；外链跳转二次重定向按需求待确认策略采取拒绝或重新校验并记录。
- [ ] **Step 4: 运行测试并在 Android 模拟器验证窗口创建/关闭/置顶**，检查四类 H5 页面可用且容器未持有已关闭 Activity。

### Task 9：实现 JSBridge 协议、会话信息和角色数据

**Files:**
- Create: `android/app/src/main/java/com/qishimeng/game/bridge/BridgeResult.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/bridge/BridgeDispatcher.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/bridge/BridgeMethod.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/role/GameRole.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/role/GameRoleStore.kt`
- Create: `android/app/src/test/java/com/qishimeng/game/bridge/BridgeDispatcherTest.kt`
- Create: `android/app/src/test/java/com/qishimeng/game/role/GameRoleStoreTest.kt`
- Create: `android/app/src/androidTest/java/com/qishimeng/game/bridge/BridgeWebViewTest.kt`

**Interfaces:**
- Consumes: Tasks 4–6 的本地隐私/设备码/Token 接口、Task 8 容器。
- Produces: WebView 可调用的 `QSMH5APP` 方法注册；`getGameInfo()` 返回 `GameInfo`；`saveGameRole(params): Result<Boolean>` 与 `getGameRole(gameId, roleId): Result<GameRole|false>`。

- [ ] **Step 1: 写 Bridge 方法与角色存储测试**：逐方法核对 JSBridge 文档的参数、回调和返回码；角色索引缺失/错误、可选字段类型过滤、同索引字段合并、多角色隔离；销毁页面后迟到回调不执行。
- [ ] **Step 2: 运行测试确认失败**，执行 `cd android; .\gradlew.bat testDebugUnitTest --tests '*BridgeDispatcherTest' --tests '*GameRoleStoreTest'`。
- [ ] **Step 3: 实现 `QSMH5APP` Bridge 分发和持久化**，先校验参数、为每次调用生成一次性回调 ID、将结果回到主线程；不得将敏感值写入日志；角色字段按需求合并。
- [ ] **Step 4: 运行单测和 Android WebView Bridge 集成测试**，调用文档中的示例，确认 JSON 回包结构一致。

### Task 10：实现页面/登录 Bridge 与窗口操作

**Files:**
- Modify: `android/app/src/main/java/com/qishimeng/game/bridge/BridgeMethod.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/bridge/BridgeActions.kt`
- Modify: `android/app/src/test/java/com/qishimeng/game/bridge/BridgeDispatcherTest.kt`
- Create: `android/app/src/androidTest/java/com/qishimeng/game/bridge/BridgeNavigationTest.kt`

**Interfaces:**
- Consumes: Task 6 登录协调器、Task 8 WebContainer、Task 9 Bridge Dispatcher。
- Produces: `agreePrivacyPolicy`、`rejectPrivacyPolicy`、`requestPhoneLogin`、`notifyLoginSuccess`、`getAccountToken`、`logout`、`openMiniWindow`、`closeMiniWindow`、`changeGameUrl`、`closePage` 等清单方法。

- [ ] **Step 1: 写 Bridge 操作测试**：同意后继续状态机，拒绝撤销同意并退出当前流程；登录通知缓存 token 并打开 game；退出清 token/关闭游戏及动态弹窗/停心跳并回登录；窗口按实例操作；`changeGameUrl` 有效/无效/缺省 game 实例结果符合约定。
- [ ] **Step 2: 运行测试确认失败**，执行 `cd android; .\gradlew.bat testDebugUnitTest --tests '*BridgeDispatcherTest'`。
- [ ] **Step 3: 接通 Bridge 方法与容器/Auth 服务**；Bridge 回调按约定 `Result` 结构返回，H5 页销毁时取消待处理调用。
- [ ] **Step 4: 运行 Android 集成测试**，验证 Vue2 CDN Demo 可调用 `getAccountToken`、`closeMiniWindow`，并覆盖重入和页面关闭后的回调。

### Task 11：实现悬浮球、福利大厅和运营心跳

**Files:**
- Create: `android/app/src/main/java/com/qishimeng/game/floatball/FloatingBallController.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/network/PopupHeartbeatService.kt`
- Create: `android/app/src/main/java/com/qishimeng/game/web/PopupWindowCoordinator.kt`
- Create: `android/app/src/test/java/com/qishimeng/game/floatball/FloatingBallControllerTest.kt`
- Create: `android/app/src/test/java/com/qishimeng/game/network/PopupHeartbeatServiceTest.kt`
- Create: `android/app/src/test/java/com/qishimeng/game/web/PopupWindowCoordinatorTest.kt`
- Create: `android/app/src/androidTest/java/com/qishimeng/game/floatball/FloatingBallViewTest.kt`

**Interfaces:**
- Consumes: Task 3 Network、Task 8 WebContainer、Task 9/10 Bridge。
- Produces: `setFloatingBall(show, active)` 控制运行态；心跳 `start/stop`；`PopupWindowCoordinator.apply(response)` 依据实例和清单同步动态窗口。

- [ ] **Step 1: 写心跳与悬浮球测试**：游戏页加载后立即请求、之后按秒间隔请求，游戏关闭/登出停止；网络失败保持状态；同状态不重载图标；welfare 显示临时隐藏、关闭恢复最新运行态；left 优先 right、bottom 优先 top；拖动/点击。
- [ ] **Step 2: 写动态窗口清单测试**：相同实例忽略 URL/尺寸变化；允许多动态窗口且顺序决定层级；数组缺失不同步、空数组清动态窗口、保留四实例；无效项目跳过且不关闭有效实例。
- [ ] **Step 3: 运行目标测试确认失败**，执行 `cd android; .\gradlew.bat testDebugUnitTest --tests '*FloatingBallControllerTest' --tests '*PopupHeartbeatServiceTest' --tests '*PopupWindowCoordinatorTest'`。
- [ ] **Step 4: 实现心跳调度、弹窗清单协调及悬浮球 View**；图标 URL 无变化不重新载入；尺寸按屏幕/密度和安全区换算；福利大厅弹出/关闭联动。
- [ ] **Step 5: 运行测试并在模拟器验证悬浮球拖动、心跳更新、多弹窗层级及登出清理**。

### 阶段四：Windows GUI 母包与渠道交付

### Task 12：实现 GUI 参数校验与游戏配置管理

**Files:**
- Create: `packager/__init__.py`
- Create: `packager/models.py`
- Create: `packager/validators.py`
- Create: `packager/config_store.py`
- Create: `packager/tests/test_validators.py`
- Create: `packager/tests/test_config_store.py`

**Interfaces:**
- Produces: `GameConfig` 校验（游戏 ID/版本、桌面名、Android 包名、证书文件、alias、签名密码、PNG 图标、闪图目录、输出目录）；`config_store` 可保存配置但剔除签名密码。

- [ ] **Step 1: 写参数与配置保存测试**：合法配置通过；负数/空 ID、非法 `applicationId`、非 PNG/损坏图标、缺证书/闪图目录、不可写输出路径拒绝；保存与读取配置都不包含签名密码。
- [ ] **Step 2: 运行测试确认失败**，执行 `py -3 -m unittest discover -s packager/tests -v`。
- [ ] **Step 3: 实现数据模型、字段校验和无密码配置存储**；证书格式先通过文件识别/签名工具校验，密码不落盘、不出现在异常信息。
- [ ] **Step 4: 运行测试确认通过**，补充已有游戏配置往返保存测试。

### Task 13：实现构建资源映射与安全 Gradle 调用

**Files:**
- Create: `packager/resource_mapper.py`
- Create: `packager/build_service.py`
- Create: `packager/tests/test_resource_mapper.py`
- Create: `packager/tests/test_build_service.py`
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/res/`

**Interfaces:**
- Consumes: Task 1 Android 模板、Task 12 `GameConfig`。
- Produces: `resource_mapper.prepare(config, staging_dir)` 将 PNG 图标转换为多密度资源，并将闪图资源复制到模板约定位置；`build_service.build(config, progress_callback) -> BuildResult` 调用 Gradle Wrapper 生成已签名 APK。

- [ ] **Step 1: 固化闪图资源映射约定**：按仓库提供素材与需求确认横/竖方向、文件命名和尺寸映射；目录资源缺失时在构建前列出缺项，不静默生成不完整发布包。
- [ ] **Step 2: 写资源映射/构建调用测试**：临时目录断言复制路径、图标变体、无效资源报错；测试命令参数不含 shell 注入且密码不进入日志/配置/命令行明文记录。
- [ ] **Step 3: 运行测试确认失败**，执行 `py -3 -m unittest discover -s packager/tests -v`。
- [ ] **Step 4: 实现 staging 目录资源准备与 Gradle 子进程**；通过受控临时属性文件/环境注入签名数据并限制权限，构建结束立即清理秘密材料；输出 APK 使用用户选择的目录和可识别命名。
- [ ] **Step 5: 运行单测并以测试证书构建一个可安装样包**，检查 APK applicationId、桌面名称、图标、版本字段与签名状态。

### Task 14：实现 Tkinter 出包界面与构建进度

**Files:**
- Create: `packager/gui.py`
- Create: `packager/出包工具说明_packager.md`
- Create: `packager/tests/test_gui_workflow.py`

**Interfaces:**
- Consumes: Task 12 校验和配置、Task 13 资源/构建服务。
- Produces: Windows 可运行入口 `py -3 -m packager.gui`；表单填写 gameId、gameVersion、APP 桌面名称、Android 包名；文件/目录选择器选择签名证书、PNG 图标、闪图目录、输出目录；签名 alias/口令输入；显示进度、错误和产物路径。

- [ ] **Step 1: 写 GUI 工作流测试**：路径选择控件将正确值交给服务；构建运行时界面不冻结；成功展示 APK 路径；取消、校验失败、Gradle 失败展示可读错误；签名密码字段掩码且不被持久化。
- [ ] **Step 2: 运行 GUI 服务层测试确认失败**，执行 `py -3 -m unittest discover -s packager/tests -v`。
- [ ] **Step 3: 实现 Tkinter 表单**；构建子进程在线程中运行，UI 更新通过主线程队列；支持保存/加载除密码以外的游戏配置。
- [ ] **Step 4: 运行测试并进行 Windows GUI 手工验收**，完成至少两个游戏配置切换，证明共享同一模板、applicationId/图标/闪图/证书独立。

### Task 15：接通现有渠道分包脚本和发布验收

**Files:**
- Modify: `安卓渠道分包设计文档_android_channel_packaging.md`
- Modify: `文档导航_documentation.md`
- Create: `tools/tests/test_channel_apk.py`
- Create: `docs/安卓工程构建与联调指南_android-build-and-integration.md`

**Interfaces:**
- Consumes: Task 13 GUI 母包 APK、现有 `tools/channel_apk.py`。
- Produces: 从 GUI 生成签名母包后可执行渠道码写入；母包和渠道包签名证书一致，完整渠道码可由 APP 读取并进入 `getGameInfo` 与 `Packagecode` Header。

- [ ] **Step 1: 为现有分包脚本写回归测试**：有效 APK fixture 可写入/替换 `0x454D4348` 渠道条目；损坏 APK、无 v2/v3 签名、超长/NUL 渠道码退出非零且源文件未变。
- [ ] **Step 2: 运行脚本测试确认基线**，执行 `py -3 -m unittest discover -s tools/tests -v`。
- [ ] **Step 3: 仅在测试暴露行为与既有渠道说明不符时修正脚本**，不改变母包不重签名、原子替换和其他 Signing Block 条目保留规则。
- [ ] **Step 4: 更新出包与联调指南**：记录 Windows 环境准备、GUI 出包、验签、分包、安装、PHP Header 读取、H5 Bridge 冒烟测试和 Android/WebView 测试矩阵。
- [ ] **Step 5: 执行端到端验收**：用 GUI 产出签名 APK，`apksigner verify` 母包/渠道包并比较证书指纹，安装渠道包检查完整 `packageCode`、六个 Header、登录主流程、隐私、心跳/弹窗、更新容错与角色 Bridge。

### Task 16：完成设备兼容、联调缺口收敛和最终回归

**Files:**
- Modify: `docs/安卓工程构建与联调指南_android-build-and-integration.md`
- Modify: `文档导航_documentation.md`
- Modify: `安卓H5游戏包开发需求文档_requirements.md`（仅在联调确认改变契约时）
- Modify: `JSBridge方法清单_jsbridge.md`（仅在联调确认改变签名时）

**Interfaces:**
- Consumes: 前序所有交付物和服务端接口实际响应。
- Produces: 可复现的联调记录、已确认的接口/Bridge 约定、设备兼容验收结果和可交付 APK。

- [ ] **Step 1: 建立联调契约清单**：逐项记录初始化方法/参数/错误码、Token 无效错误码、阿里云兑换 payload、心跳响应外层结构、Bridge 调用形式/回调时机、动态弹窗 URL 策略与闪图映射；未确认项标记责任人与阻塞范围，不将猜测写作既定事实。
- [ ] **Step 2: 执行测试套件**：`cd android; .\gradlew.bat testDebugUnitTest lintDebug assembleDebug`；`py -3 -m unittest discover -s packager/tests -v`；`py -3 -m unittest discover -s tools/tests -v`。预期全部通过，无 lint error。
- [ ] **Step 3: 验证 Android 8、10、13、15**：记录设备/API、Android System WebView 版本、WebView/Bridge 行为、窗口适配、登录/隐私/外链和安装验收结果。
- [ ] **Step 4: 对照需求逐项验收**：确认需求文档第 4–9 节每条都有通过的自动或设备测试；未能验证的项明确列出，不能宣称已完成。
- [ ] **Step 5: 更新导航和联调文档**，新增路径和当前状态；最终 review 检查 secrets、固定域名、包名参数化、构建可复现和签名验证。

## 外部契约与实施前检查项

这些项目在需求文档中仍标记待服务端或联调确认；可先以接口适配器和 fake 测试开发，但进入真实联调前必须拿到实际约定：

- 初始化 POST 参数、鉴权、非 `code=0` 处理、缺字段和超时规则。
- 服务端判定 Token 失效/过期的唯一错误码；普通业务失败不得触发清除。
- 阿里云 `exchange_url` 请求体中 `auth_token` 字段名和其他必需字段；成功/失败原始响应形态。
- 心跳请求方法/参数及响应外层包裹结构；需求样例给出的 `popup_pages`、`floating_ball` 业务字段保持不变。
- H5 Bridge 的最终 Android 注入/调用签名、回调协议、回调超时/页面销毁规则，以及拒绝隐私调用的返回约定。
- 闪图目录的横竖方向命名、尺寸映射和缺图策略；签名文件 alias/证书口令来源与证书格式支持列表。
- 动态弹窗 URL 的可加载安全策略、重定向外链是否二次校验；Bridge 来源独立信任策略。

## 自审

- **需求覆盖：** Android 工程配置与构建、初始化/状态机、Header、隐私/设备码、Token/阿里云/H5 登录、在线更新、WebView 与 URL 规则、全部 JSBridge、角色数据、心跳/多窗口/悬浮球、GUI 多游戏出包、签名、渠道分包、Android 设备兼容均对应具体任务。
- **步骤粒度：** 每项先写行为测试，再实现，再运行单测或设备验收；跨系统问题按可独立验收的模块和阶段拆分。
- **接口一致性：** `GameInfo`、`ApiResult`、`WindowSpec`、`GameConfig` 在全计划使用统一字段；Token 交换路径始终取初始化配置 `exchange_url`。
- **Review Focus：** 五项风险逐一落到 Task 2/3、Task 5、Task 4/9、Task 11、Task 12–14 的测试。
- **比例：** 计划明确交付结构、依赖接口和测试条件，不预写实现代码；服务端未明确的事项列为外部契约，不擅自补造。
