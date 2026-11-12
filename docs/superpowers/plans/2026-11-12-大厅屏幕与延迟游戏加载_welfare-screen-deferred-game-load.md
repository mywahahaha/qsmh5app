# 大厅屏幕与延迟游戏加载 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 Android 福利大厅横屏打开、可选择延迟的游戏地址切换、关闭大厅后的实际方向布局和悬浮球无跳动恢复，并移除不再需要的自定义 `screenChange` 通知协议。

**Architecture:** `MainActivity` 负责方向切换完成后的统一布局事务，`WebContainer` 负责游戏 URL 的立即加载与单槽延迟队列，`BridgeActions` 解析 `waitCloseWelfarepage` 和 `loadPendingGameUrl`。H5 不接收原生方向通知，只依赖实际 viewport 和标准 `resize`；悬浮球由原生在每次隐藏转显示前按初始化配置重新定位。

**Tech Stack:** Kotlin、Android WebView/JavascriptInterface、JUnit/Robolectric、AndroidX Test、JavaScript/Node.js、Markdown。

**Spec:** 本对话于 2026-11-12 确认的最终需求，以及 `安卓H5游戏包开发需求文档_requirements.md`。

## Global Constraints

- 始终使用中文；Markdown 文件名遵循“中文标题_english.md”。
- Windows 环境只修改 Android 与共用协议、示例和文档，不修改 `ios/`。
- 当前 `master` 工作树已有用户和本任务的未提交修改；用户已选择 Native 方式并要求在当前工作区增量完成，不创建工作树、不提交、不覆盖无关修改。
- `closeWelfarepage` 大小写固定不变。
- `changeGameUrl.waitCloseWelfarepage` 缺失时按 `false`；`closeWelfarepage.loadPendingGameUrl` 缺失时按 `false`。
- 不保留或广播自定义 `screenChange` 协议；方向变化后仍必须重排所有已打开 H5。
- 悬浮球每次从隐藏恢复显示前，必须按初始化定位配置和当前可见区域重新定位，定位完成后才显示。

## Review Focus

- 普通关闭大厅时即使存在历史 `pendingGameUrl` 也必须清除且不得重载游戏，由 Task 2 的纯关闭测试覆盖。
- 延迟 URL 只允许被一次 `loadPendingGameUrl=true` 的专用关闭消费；重复关闭、旧关闭接口和退出登录不得再次加载，由 Task 2/3 覆盖。
- 目标方向与当前方向相同时不会触发 `onConfigurationChanged`，仍必须完成布局、加载和悬浮球恢复，由 Task 3 覆盖。
- 无效 `mode` 时只是不旋转，若明确要求消费 pending，仍按当前实际方向加载；没有 pending 时不得重载当前页，由 Task 2/3 覆盖。
- 删除通知协议后，已有 H5 的容器重排、新开页面和更新 URL 页面仍必须使用当前实际方向，由 Task 1/3 覆盖。

---

### Task 1: 移除自定义屏幕通知并保留页面重排

**Files:**
- Delete: `android/app/src/main/java/com/qishimeng/game/device/ScreenChangeTracker.kt`
- Delete: `android/app/src/test/java/com/qishimeng/game/device/ScreenChangeTrackerTest.kt`
- Modify: `android/app/src/main/java/com/qishimeng/game/MainActivity.kt`
- Modify: `android/app/src/main/java/com/qishimeng/game/web/WebContainer.kt`
- Modify: `android/app/src/main/java/com/qishimeng/game/bridge/BridgeScript.kt`
- Modify: `android/app/src/test/java/com/qishimeng/game/bridge/BridgeScriptTest.kt`
- Modify: `示例/bridge-script.test.js`
- Modify: `示例/qsm-jsbridge.js`
- Modify: `示例/qsm-jsbridge.test.js`
- Modify: Android 仪器测试中的通知用例。

**Interfaces:**
- Preserves: `WebContainer.relayoutAll(onComplete: () -> Unit)`。
- Removes: `WebContainer.notifyAll`、`window.__QSMH5APP_NOTIFY__`、当前仅用于通知的 `QSMH5APP.on/off` 实现。

- [x] 删除/改写通知相关测试，使测试只断言方向变化后已有页面铺满当前宿主，新打开页面也使用当前尺寸。
- [x] 运行相关 JS/Kotlin 测试，确认旧通知断言失败或编译失败。
- [x] 删除通知协议实现和追踪器，保留实际方向稳定后执行 `relayoutAll`。
- [x] 运行 JS、Bridge 和页面布局测试，确认通过且生产代码、示例和业务文档无旧方向通知协议残留。

### Task 2: 增加游戏 URL 延迟队列与关闭消费语义

**Files:**
- Modify: `android/app/src/main/java/com/qishimeng/game/bridge/BridgeActions.kt`
- Modify: `android/app/src/main/java/com/qishimeng/game/web/WebContainer.kt`
- Modify: `android/app/src/test/java/com/qishimeng/game/bridge/WelfarePageCloseTest.kt`
- Modify/Create: WebContainer 延迟导航单元测试。
- Modify: `android/app/src/androidTest/java/com/qishimeng/game/bridge/BridgeInjectionTest.kt`

**Interfaces:**
- Produces: `changeGameUrl(url, waitCloseWelfarepage)`，立即模式清除旧 pending 并加载；延迟模式只覆盖单槽 pending。
- Produces: `closeWelfarepage({mode,loadPendingGameUrl})`，默认丢弃 pending；显式为 true 时一次性取出 pending 供方向布局完成后加载。
- Preserves: `closePage`、`closeMiniWindow` 只关闭页面、不旋转，并在关闭大厅时丢弃 pending。

- [x] 先写失败测试，覆盖默认立即加载、延迟不加载、纯关闭清除、显式消费一次、无 pending 不重载、旧关闭接口清除。
- [x] 运行定向测试观察 RED。
- [x] 实现单槽 pending 的覆盖、原子 take 和 clear，并把专用关闭事务交给 MainActivity 协调。
- [x] 运行定向单元测试和 Bridge 编译，确认 GREEN。

### Task 3: 完成实际方向布局、游戏加载和悬浮球恢复事务

**Files:**
- Modify: `android/app/src/main/java/com/qishimeng/game/MainActivity.kt`
- Modify: `android/app/src/main/java/com/qishimeng/game/floatball/FloatingBallView.kt`
- Modify: `android/app/src/androidTest/java/com/qishimeng/game/floatball/MainActivityFloatingBallTest.kt`
- Modify: `android/app/src/test/java/com/qishimeng/game/web/WebPagePresentationTest.kt`

**Interfaces:**
- Produces: 同方向和跨方向均执行“关闭大厅→实际宿主布局稳定→重排 H5→可选加载 pending→重定位并显示悬浮球”。
- Consumes: Task 2 的 pending URL take/clear 能力。

- [x] 写失败仪器/单元测试，覆盖横转竖、竖转横、同方向、无效 mode、纯关闭、延迟加载和悬浮球显示前定位。
- [x] 运行测试确认 RED。
- [x] 实现统一完成回调；跨方向在 `onConfigurationChanged` 后执行，同方向在当前宿主布局稳定后直接执行。
- [x] 游戏新文档按当前实际方向和 Header 加载；页面可见后定位并恢复悬浮球。
- [x] 运行方向、WebView、悬浮球和角色清理回归测试；新增仪器测试已编译，因当前无连接设备未执行。

### Task 4: JS 示例、公共包装器和文档同步

**Files:**
- Modify: `示例/qsm-jsbridge.js`
- Modify: `示例/qsm-jsbridge.test.js`
- Modify: `示例/vue2-jsbridge-demo.html`
- Modify: `JSBridge方法清单_jsbridge.md`
- Modify: `安卓H5游戏包开发需求文档_requirements.md`
- Modify: `docs/初始化接口字段说明_initialization-api.md`
- Modify: `docs/安卓工程构建与联调指南_android-build-and-integration.md`（如验证状态变化）。

**Interfaces:**
- Documents: `changeGameUrl({url,waitCloseWelfarepage})` 和 `closeWelfarepage({mode,loadPendingGameUrl})` 的默认值、顺序和清理规则。
- Removes: 所有 `screenChange` 接收示例和协议描述。

- [x] 更新 JS 包装器及测试，展示立即切换和大厅延迟切换两种调用方式。
- [x] 更新 Vue2 示例，H5 使用标准 `resize`/`ResizeObserver` 适配。
- [x] 同步需求、Bridge 清单和初始化定位说明。
- [x] 运行 Node 测试、Android 全量单元测试、Debug 构建和 AndroidTest 编译。
- [x] 执行 `git diff --check`、确认无 `ios/` 修改，并做最终代码审查。

## 验证记录（2026-11-12）

- `node 示例/bridge-script.test.js`：5/5 通过。
- `node 示例/qsm-jsbridge.test.js`：通过。
- `:app:testDebugUnitTest`：174 项通过。
- `:app:assembleDebug`：通过。
- `:app:compileDebugAndroidTestKotlin`：通过。
- `git diff --check`：无错误，仅提示两个既有 Kotlin 测试文件后续可能由 LF 转为 CRLF。
- `git diff --name-only -- ios`：无输出。
- Android 连接设备：无；新增仪器测试保留待真机执行。
