# iOS 大厅方向与延迟游戏加载改造指南

日期：2026-11-12
状态：供 iOS 端开发、测试和联调实施
适用范围：`ios/` 原生工程、iOS Bridge 资源及 iOS 测试。本文在 Windows 环境整理，不包含 iOS 源码修改。

## 1. 背景与目标

Android 已按 Native 方式完成福利大厅横屏打开、关闭后方向事务、延迟游戏地址加载、所有 H5 页面重排和悬浮球重新定位。iOS 需要对齐同一套 H5 调用协议和用户体验。

本次 iOS 改造目标：

1. 游戏为竖屏时，打开福利大厅前先切换横屏，实际横屏布局稳定后再显示大厅。
2. 游戏已经横屏时，直接打开大厅，不重复发起系统方向切换。
3. 关闭大厅不自动恢复“进入大厅前方向”，H5 通过 `closeWelfarepage.mode` 明确指定后续方向。
4. `changeGameUrl` 既可单独立即使用，也可先保存地址，等待大厅关闭和方向稳定后再加载。
5. 普通关闭大厅不得误加载历史待处理地址。
6. 方向变化后，所有已打开的 WKWebView、原生一键登录页、原生更新弹窗和悬浮球都按当前实际窗口重新布局。
7. 悬浮球从隐藏恢复显示时，先按初始化配置和当前窗口重新定位，再显示，不能让用户看到旧位置跳动。
8. 不新增 Native→H5 私有方向通知；H5 使用标准 viewport 变化能力自行适配。
9. Native 单独保存不可变的出包方向 `packageMode`；退出游戏时恢复该方向，等待物理方向和根视图布局稳定后，再显示一键登录或 H5 登录。

## 2. 明确不做的事情

- Native 可为 `getWelfareInfo` 临时保存大厅 `entryMode`，但不使用它自动恢复方向。
- 不发送 `screenChange` 或其他私有方向事件。
- `closePage`、`closeMiniWindow` 不负责旋转屏幕。
- `closePage`、`closeMiniWindow` 不消费或加载待处理游戏地址。
- 不重建已有 `web_game` WKWebView；切换游戏地址继续复用原实例。
- 不修改 H5 游戏内部横竖屏布局实现，Native 只保证正确的 WebView 尺寸和标准 viewport 变化。
- 大厅进入另一款游戏时使用目标游戏方向，不恢复 `packageMode`；只有退出当前游戏并重新进入认证流程才恢复。

## 3. 最终 JSBridge 协议

### 3.1 `getWelfareInfo`

```js
QSMH5APP.getWelfareInfo({}, function (result) {
  if (result.code !== 0) return;
  var entryMode = result.data.entryMode;
  var currentMode = result.data.currentMode;
});
```

返回：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "entryMode": "vertical",
    "currentMode": "horizontal"
  }
}
```

规则：

- 仅当前活动的 `web_welfare` 文档可以调用；其他页面或无有效大厅会话时返回 `code=1001`。
- Native 必须在将业务方向选择为 `horizontal` 之前记录 `entryMode`。
- 竖屏游戏打开大厅返回 `entryMode=vertical`；横屏游戏打开大厅返回 `entryMode=horizontal`。
- `currentMode` 读取调用时的业务方向，大厅正常显示后通常为 `horizontal`。
- 旋转等待期间重复点击悬浮球或重复请求打开大厅，不得覆盖首次记录的 `entryMode`。
- 大厅已经位于最上层时重复调用 `openMiniWindow`，不得开启新会话或覆盖 `entryMode`。
- 大厅从游戏后方重新置顶时视为新一轮进入，按重新置顶前的业务方向更新 `entryMode`。
- 大厅关闭、打开取消、退出登录或会话失效时清除。
- 该值只供 H5 决策，Native 不使用它自动恢复方向，也不影响 `getGameInfo.screenMode` 和 Header `Screenmode`。

纯关闭并回到进入大厅前方向：

```js
GameBridge.getWelfareInfo().then(function (info) {
  return GameBridge.closeWelfarepage(info.entryMode, false);
});
```

大厅进入新游戏时应使用目标游戏方向，不使用 `entryMode`。

### 3.2 `changeGameUrl`

```js
QSMH5APP.changeGameUrl({
  url: 'https://box.74meng.com/h5?gid=266',
  waitCloseWelfarepage: false
}, callback);
```

参数：

| 参数 | 类型 | 默认值 | 规则 |
| --- | --- | --- | --- |
| `url` | string | 无 | 必填；沿用 iOS 当前可信 origin 校验 |
| `waitCloseWelfarepage` | boolean | `false` | 是否等待专用大厅关闭接口后再加载 |

行为：

- 缺失或为 `false`：
  - 清除旧的 `pendingGameURL`。
  - 立即复用或创建 `web_game`，加载新 URL 并置顶。
  - 可独立调用，不依赖后续 `closeWelfarepage`。
- 为 `true`：
  - 只覆盖保存单槽 `pendingGameURL`。
  - 不导航游戏、不置顶游戏、不恢复悬浮球。
  - 后续只有 `closeWelfarepage({loadPendingGameUrl:true})` 可以消费。
- `waitCloseWelfarepage` 存在但不是布尔值时返回参数错误。
- URL 无效时返回参数错误，不得覆盖已有 pending。
- 成功回调表示原生接受请求，不表示远端页面加载完成。

### 3.3 `closeWelfarepage`

```js
QSMH5APP.closeWelfarepage({
  mode: 'vertical',
  loadPendingGameUrl: false
}, callback);
```

参数：

| 参数 | 类型 | 默认值 | 规则 |
| --- | --- | --- | --- |
| `mode` | string | 无 | `horizontal` / `vertical` 时切换方向；其他值只关闭大厅 |
| `loadPendingGameUrl` | boolean | `false` | 是否在方向和布局稳定后加载 pending |

行为：

- 固定关闭 `web_welfare`，不依赖调用方所在实例名称。
- `mode` 为合法值时：
  - 立即更新业务方向。
  - `getGameInfo.data.screenMode` 和 Header `Screenmode` 立即使用新值。
  - 随后请求系统旋转；不等待系统动画结束才更新业务方向。
- `mode` 缺失、非字符串或不是两个合法值：
  - 正常关闭大厅。
  - 不旋转，不返回 mode 参数错误。
  - 后续布局和 pending 加载使用当前实际方向。
- `loadPendingGameUrl` 缺失或为 `false`：
  - 清除 pending。
  - 只关闭和按需旋转，不重载游戏。
- `loadPendingGameUrl=true`：
  - 在关闭事务开始时一次性取出 pending，防止重复消费。
  - 等目标方向和宿主布局稳定后再加载该 URL。
  - 没有 pending 时不得刷新或重载当前游戏。
- `loadPendingGameUrl` 存在但不是布尔值时返回参数错误，不开始关闭事务。
- 如果 `web_welfare` 未打开，返回 `data=false`；合法 `mode` 仍按 Android 语义更新并请求方向，但已取出的 pending 直接丢弃，不得加载游戏。
- 返回 `Result.data` 表示 `web_welfare` 是否实际关闭成功；方向请求不改变关闭结果。

### 3.4 旧关闭接口

```js
QSMH5APP.closePage({}, callback);
QSMH5APP.closeMiniWindow({page: 'web_welfare'}, callback);
```

当目标为 `web_welfare` 时：

- 关闭页面。
- 清除 `pendingGameURL`。
- 不切换方向。
- 不加载游戏地址。

### 3.5 大厅进入新游戏的标准调用顺序

```js
GameBridge.changeGameUrl(gameUrl, true).then(function (accepted) {
  if (!accepted) return false;
  return GameBridge.closeWelfarepage(targetMode, true);
});
```

必须先调用 `changeGameUrl(..., true)`，再调用 `closeWelfarepage(..., true)`。第二个接口只消费已经保存的地址。

纯关闭大厅示例：

```js
GameBridge.closeWelfarepage('vertical', false);
```

立即独立切换游戏示例：

```js
GameBridge.changeGameUrl(gameUrl, false);
```

### 3.6 `logout`

`logout` 的 Bridge 参数和返回结构保持不变，但 Native 后续流程增加出包方向恢复事务：

```js
QSMH5APP.logout({}, function (result) {
  // code=0 只表示退出流程已受理，不表示方向恢复或重新认证已经完成。
});
```

规则：

- 清理 Token、旧游戏会话、福利大厅、运营弹窗、悬浮球状态、pending URL 和未完成的大厅事务。
- 将业务方向立即恢复为不可变的 `packageMode`，因此后续 `getGameInfo.screenMode` 和 Header `Screenmode` 使用出包方向。
- 如果实际方向不同，先请求系统旋转；如果本来已经是出包方向，不重复发起系统旋转。
- 无论是否发生旋转，都必须等待根视图 bounds 与目标方向一致并完成布局。
- 方向和布局完成前，不得展示一键登录，也不得创建 `web_login`，避免登录界面从旧方向跳到新方向。
- 完成后直接从一键登录/H5 登录步骤继续，不重跑初始化、更新检查或旧 Token 自动登录。

## 4. 方向状态定义

iOS 实现必须区分三类状态：

### 4.1 业务方向

业务方向对应现有 `ScreenOrientationController.requestedMode`：

- 初始值取出包配置。
- 成功调用 `changeScreenmode` 后立即更新。
- 打开福利大厅时立即更新为 `horizontal`。
- `closeWelfarepage` 传合法 `mode` 时立即更新为该值。
- `getGameInfo.data.screenMode` 和所有原生请求 Header `Screenmode` 读取业务方向。

### 4.2 实际布局方向

实际布局方向由当前 `UIWindow` / `UIViewController.view.bounds` 决定，只用于判断视图何时可以安全重排和加载：

- 横屏：有效窗口宽度大于高度。
- 竖屏：有效窗口高度大于宽度。
- 同方向请求也必须经过一次布局完成步骤，不能依赖 `viewWillTransition` 一定触发。
- `requestGeometryUpdate` 的 completion 只表示系统接受或拒绝请求，不能单独作为页面布局完成依据。

### 4.3 不可变出包方向 `packageMode`

`packageMode` 与可变的 `requestedMode` 必须分开保存：

- 初始化时优先从 `Info.plist` 的 `QSMInitialScreenMode` 读取 `horizontal` 或 `vertical`，缺失时按 `UISupportedInterfaceOrientations` 的首选方向兜底。
- `packageMode` 初始化后不可被 `changeScreenmode`、打开大厅或 `closeWelfarepage.mode` 修改。
- `requestedMode` 仍表示当前业务方向，可以在游戏运行期间多次变化。
- `logout` 固定以 `packageMode` 为恢复目标，不读取大厅 `entryMode`，也不使用“退出前最后方向”。
- 新进程启动、退出恢复和重新认证后的首个游戏会话，都以同一个 `packageMode` 为基准。

建议结构：

```swift
@MainActor final class ScreenOrientationController {
    let packageMode: ScreenMode
    private(set) var requestedMode: ScreenMode

    init(bundle: Bundle = .main) {
        let mode = Self.readPackageMode(from: bundle)
        packageMode = mode
        requestedMode = mode
    }

    func restorePackageMode(in window: UIWindow?, root: UIViewController?) {
        change(to: packageMode, in: window, root: root)
    }
}
```

## 5. 推荐职责分层

### 5.1 `AppCoordinator`：业务状态和 pending 所有者

建议新增状态：

```swift
private var pendingGameURL: URL?
private var pendingWelfareClose: PendingWelfareClose?
private var pendingLogoutRestore: PendingLogoutRestore?
private var welfareCloseInProgress = false
```

建议的数据结构：

```swift
struct PendingWelfareClose {
    let id: UUID
    let targetMode: ScreenMode?
    let gameURL: URL?
}

struct PendingLogoutRestore {
    let id: UUID
    let targetMode: ScreenMode
    let epoch: Int
}
```

职责：

- 校验 Bridge 参数和 URL。
- 维护单槽 `pendingGameURL`。
- 开始关闭时取出或清除 pending。
- 管理窗口栈和悬浮球业务状态。
- 收到 Root 的“方向和布局完成”回调后，才导航新游戏。
- `logout`、重新初始化、关闭游戏、会话失效时清除 pending 并取消未完成事务。
- `logout` 创建独立的恢复事务；只有事务 ID、epoch 和目标方向仍匹配时才进入认证流程。

### 5.2 `RootViewController`：物理方向和布局事务所有者

建议增加 ViewEvent：

```swift
case openWelfareAfterLandscape
case closeWelfare(PendingWelfareClose)
case restorePackageModeForLogout(PendingLogoutRestore)
```

或者使用等价的闭包/请求对象，关键是 Root 必须负责：

1. 保持悬浮球隐藏。
2. 移除或关闭福利页面。
3. 请求目标方向。
4. 等待根视图实际尺寸符合目标方向。
5. 调用 `view.layoutIfNeeded()`。
6. 重新计算所有页面、更新弹窗、一键登录容器和悬浮球层。
7. 通知 Coordinator 事务完成。

退出恢复期间还必须保证旧会话页面已销毁、悬浮球保持隐藏，并在完成回调前不展示任何登录 UI。

### 5.3 `ScreenOrientationController`：业务方向和系统请求

现有 `requestedMode` 可继续作为业务方向来源。建议：

- `change(to:)` 先更新 `requestedMode`，再请求系统方向。
- 增加不可变 `packageMode` 和 `restorePackageMode(...)`；恢复仍复用统一的系统方向请求逻辑。
- 如果目标等于当前业务方向，不重复发起系统请求。
- 不把 `requestGeometryUpdate` 回调当作布局完成回调。
- Root 通过 `viewWillTransition(to:with:)` completion、`viewDidLayoutSubviews` 和实际 bounds 共同判定稳定。

### 5.4 `WebPageController` / `WebPageLayout`：页面尺寸

当前 Root 的 `viewDidLayoutSubviews` 已遍历 `pages.values` 重算 frame，应保留并抽成可显式调用的方法，例如：

```swift
private func relayoutAllPages() {
    let safeArea = view.safeAreaLayoutGuide.layoutFrame
    for page in pages.values {
        page.view.frame = WebPageLayout.frame(
            for: page.spec,
            bounds: view.bounds,
            safeArea: safeArea
        )
    }
}
```

要求：

- 已打开的所有 WKWebView 都重排，不只处理 `web_game` 或 `web_welfare`。
- 方向完成后再加载 pending URL，确保新文档初始 viewport 正确。
- 之后新打开页面直接使用当前 bounds。
- `changeGameUrl` 继续复用原 `web_game` 控制器和 WKWebView。

## 6. 关键事务时序

### 6.1 竖屏游戏打开大厅

```text
点击悬浮球 / openMiniWindow(web_welfare)
→ Coordinator 将业务方向改为 horizontal
→ 悬浮球保持隐藏
→ Root 请求横屏
→ 等待根视图宽 > 高且完成 layout
→ 重排所有已打开页面和原生覆盖层
→ 创建或置顶 web_welfare
```

如果本来已经横屏：跳过系统旋转，但仍在当前布局周期确认尺寸后打开大厅。

### 6.2 纯关闭大厅并切方向

```text
closeWelfarepage({mode:'vertical',loadPendingGameUrl:false})
→ 清除 pendingGameURL
→ 隐藏悬浮球
→ 关闭 web_welfare
→ 业务方向立即改为 vertical
→ 请求竖屏
→ 等待根视图高 > 宽且完成 layout
→ 重排所有页面和原生覆盖层
→ 按初始化配置重新定位悬浮球
→ 显示悬浮球
```

### 6.3 大厅进入新游戏

```text
changeGameUrl({url,waitCloseWelfarepage:true})
→ 保存/覆盖 pendingGameURL，不导航

closeWelfarepage({mode:'horizontal'|'vertical',loadPendingGameUrl:true})
→ 一次性取出 pendingGameURL
→ 关闭大厅并按需旋转
→ 方向和布局稳定
→ 将 gamePageReady 置为 false
→ 复用 web_game WKWebView 导航新 URL
→ 新游戏主文档可见
→ 按初始化配置重新定位悬浮球
→ 显示悬浮球
```

### 6.4 无效 mode 但需要加载 pending

```text
closeWelfarepage({mode:'invalid',loadPendingGameUrl:true})
→ 关闭大厅
→ 不旋转
→ 使用当前实际窗口完成重排
→ 加载一次 pending URL
```

### 6.5 退出游戏恢复出包方向

```text
logout
→ Bridge 成功回调表示已受理
→ 清除 Token、pending URL 和未完成的大厅事务
→ 销毁游戏、大厅、运营弹窗等旧会话页面
→ 隐藏并重置悬浮球会话状态
→ Coordinator 创建 PendingLogoutRestore(id, targetMode: packageMode, epoch)
→ ScreenOrientationController 将 requestedMode 恢复为 packageMode
→ 如有需要，请求系统旋转
→ Root 等待实际 bounds 符合 packageMode 并完成 layout
→ 重排/刷新原生覆盖层所需尺寸
→ 校验 transaction id、epoch 和当前认证状态
→ 清除退出恢复事务
→ 启动一键登录；不可用或失败时再创建全新的 web_login
```

关键约束：

- 竖屏包无论运行中经历多少次横竖屏切换，退出后都回到竖屏；横屏包同理。
- 同方向退出也必须经过布局确认，但不能依赖一定会收到 `viewWillTransition`。
- 一键登录模型和 H5 登录窗口必须在恢复完成后，按当前实际窗口尺寸创建。
- 旧页面的异步 Bridge 回调、旧方向事务和旧 pending URL 均不得在新登录会话中生效。
- `closePage`、`closeMiniWindow`、`closeWelfarepage` 和大厅进入新游戏不触发本事务。

## 7. pending 生命周期和防残留规则

| 操作 | pending 处理 |
| --- | --- |
| `changeGameUrl(wait=false)` | 先清除，再立即加载新 URL |
| `changeGameUrl(wait=true)` | 覆盖保存单槽 URL |
| `closeWelfarepage(load=false)` | 清除，不加载 |
| `closeWelfarepage(load=true)` | 取出一次，稳定后加载 |
| `closePage` 关闭大厅 | 清除，不加载 |
| `closeMiniWindow` 关闭大厅 | 清除，不加载 |
| 新建一轮大厅会话 | 清除上轮残留 |
| `logout` / 重新初始化 | 清除并取消未完成事务 |
| 关闭 `web_game` | 清除并取消未完成事务 |
| 没有 pending | 不刷新、不重载当前游戏 |

pending 必须单槽覆盖，不使用数组队列；大厅只会进入一个最终选择的游戏。

## 8. 悬浮球改造要求

当前 iOS `FloatingBallView.layout(in:)` 在容器尺寸未变化时直接返回，因此无法在“隐藏→显示且方向相同”时清除旧拖动位置。需要增加强制按初始化配置重新定位的能力，例如：

```swift
func layout(in size: CGSize, resetToConfiguration: Bool = false) {
    guard resetToConfiguration || size != containerSize else { return }
    containerSize = size
    frame = FloatingBallLayout.frame(
        configuration: configuration,
        width: size.width,
        height: size.height
    )
    imageView.frame = bounds
}
```

从隐藏恢复显示时必须按以下顺序：

```text
保持 isHidden=true
→ 更新 floatingBallLayer.frame
→ layout(resetToConfiguration:true)
→ root.view.layoutIfNeeded()
→ 下一主线程周期确认事务仍有效
→ isHidden=false
```

注意：

- 不恢复隐藏前拖动坐标。
- 普通显示期间仍允许纵向拖动。
- 图标 URL 和 active 未变化时不重新下载图片。
- 大厅关闭并加载新游戏时，必须等新游戏主文档可见后再显示。
- 纯关闭不加载游戏时，可在方向布局完成后直接显示。

## 9. 原生页面与 H5 页面布局

方向变化后至少处理：

- 所有 `WebPageController.view`。
- `WKWebView` 自适应尺寸。
- `FloatingBallLayer` 和悬浮球。
- 原生更新弹窗 `UpdatePromptView`。
- 阿里云一键登录授权页及自定义 UI。
- 启动背景、闪图、状态提示层。

一键登录和更新弹窗不得沿用旋转前的固定 frame。若第三方授权页由 SDK 管理，应在方向更新后重新提交布局配置或调用 SDK 提供的刷新接口；如果 SDK 不支持动态刷新，应关闭旧授权页并按当前方向重新创建，且不得复用旧方向缓存尺寸。

## 10. H5 适配要求

Native 不发送私有方向通知。H5 使用：

```js
function relayoutByViewport() {
  var width = window.innerWidth;
  var height = window.innerHeight;
  // 更新 Canvas、WebGL、rem 或横竖屏组件。
}

window.addEventListener('resize', relayoutByViewport);
if (window.visualViewport) {
  window.visualViewport.addEventListener('resize', relayoutByViewport);
}
var observer = window.ResizeObserver
  ? new ResizeObserver(relayoutByViewport)
  : null;
if (observer) observer.observe(document.documentElement);
```

页面销毁时移除监听并断开 `ResizeObserver`。

## 11. iOS 文件修改建议

| 文件 | 建议修改 |
| --- | --- |
| `ios/Resources/bridge.js` | 增加 `getWelfareInfo`、`closeWelfarepage`；保留新参数透传；移除不再属于协议的 `bridge.on/off` 空实现 |
| `ios/Core/AppCoordinator.swift` | 在选择横屏前记录大厅 `entryMode`；增加 pending、关闭和退出恢复事务；扩展 `changeGameUrl`；处理 `getWelfareInfo`、`closeWelfarepage`；logout 清理上下文和旧事务，恢复完成后再认证 |
| `ios/App/AppDelegate.swift` | `ScreenOrientationController` 分离不可变 `packageMode` 与可变 `requestedMode`；方向请求与布局完成解耦 |
| `ios/App/RootViewController.swift` | 打开大厅、关闭大厅和退出恢复方向事务；所有页面/原生层重排；完成回调；悬浮球显示前定位 |
| `ios/Features/FloatingBallView.swift` | 增加强制恢复初始化位置的方法 |
| `ios/Features/WebPageController.swift` | 确保方向稳定后导航 pending URL，复用现有 WKWebView |
| `ios/Features/WebPageLayout.swift` | 继续按当前 bounds/safeArea 计算；增加横竖屏代表性测试 |
| `ios/Tests/bridge.test.cjs` | 新接口名和参数透传测试，删除方向通知相关断言 |
| `ios/Tests/CoreTests.swift` | pending 单槽、清理、消费一次、Header/GameInfo 方向同步 |
| `ios/Tests/WebPageLayoutTests.swift` | 所有页面在横竖屏下的 frame 和同方向重排 |
| `ios/Tests/FloatingBallTests.swift` | 隐藏后显示恢复初始化位置，显示前不可见 |
| `ios/UITests/BootstrapUITests.swift` | 完整用户场景和视觉时序 |

## 12. 必测场景

### 12.1 Bridge 和状态单测

- `changeGameUrl` 缺失 `waitCloseWelfarepage` 时立即加载。
- `getWelfareInfo` 仅允许当前大厅调用，竖屏/横屏入场分别返回正确 `entryMode`。
- 旋转等待期间重复打开和大厅已置顶时重复打开，不覆盖 `entryMode`。
- 大厅关闭、打开取消、退出登录后 `getWelfareInfo` 上下文失效。
- 延迟模式不提前导航、不置顶、不显示球。
- 两次延迟调用只保留最后一个 URL。
- 纯关闭清除 pending，不重载游戏。
- 显式消费只加载一次。
- 没有 pending 时不重载。
- `closePage`、`closeMiniWindow`、`logout` 清理 pending。
- 无效 mode 只关闭，不旋转。
- `changeScreenmode`、打开大厅、合法关闭 mode 后，GameInfo 和 Header 立即使用新业务方向。
- 不存在 `web_welfare` 时，关闭结果为 false；合法 mode 的方向请求行为需与 Android 保持一致。
- `packageMode` 从 `QSMInitialScreenMode` 读取且在多次业务方向变化后保持不变。
- `logout` 立即把业务方向恢复为 `packageMode`，并清除大厅、pending 和旧方向事务。

### 12.2 布局和 UI 测试

1. 竖屏游戏 → 打开大厅 → 横屏大厅铺满宿主。
2. 竖屏游戏 → 打开大厅 → 关闭并回竖屏 → 悬浮球无位置跳动。
3. 竖屏游戏 → 大厅进入横屏游戏 → 方向稳定后加载 URL，复用游戏 WKWebView。
4. 横屏游戏 → 打开/关闭大厅 → 不重复旋转。
5. 横屏游戏 → 大厅进入竖屏游戏 → 竖屏稳定后加载 URL。
6. 实际方向变化后，所有已打开 H5 页面铺满各自新 frame。
7. 方向变化后新打开 H5 使用当前尺寸。
8. `changeGameUrl` 新文档首帧的 viewport 与当前方向一致。
9. 一键登录和在线更新原生页面在当前方向内完整显示。
10. 悬浮球隐藏期间改变方向，恢复时直接出现在初始化位置。
11. 悬浮球隐藏后同方向恢复，也重置为初始化位置。
12. 快速重复关闭或会话失效后，旧完成回调不得加载 URL 或显示球。
13. 竖屏包多次横竖屏切换后退出，先恢复竖屏并完成布局，再展示一键登录/H5 登录。
14. 横屏包多次横竖屏切换后退出，先恢复横屏并完成布局，再展示一键登录/H5 登录。
15. 退出时已经处于 `packageMode`，不重复请求旋转，但登录 UI 仍需等待当前布局确认。

## 13. 事务失效与并发保护

建议每次打开/关闭方向事务生成 UUID，并同时记录当前 `epoch`：

- Root 完成布局时，只有 transaction ID 和 epoch 仍匹配才继续。
- 新事务开始时取消或替换旧事务。
- 页面、会话或 Root 销毁后，排队的主线程回调直接丢弃。
- 新游戏导航成功前保持 `gamePageReady=false`。
- WKWebView 主文档可见后再恢复悬浮球。

避免以下竞态：

- 系统方向动画尚未完成就加载游戏，导致初始 viewport 错误。
- 旧关闭事务在新会话中加载历史 URL。
- 大厅关闭事件先显示悬浮球，随后方向变化造成球体跳动。
- 同方向没有系统回调，事务永久不完成。
- `requestGeometryUpdate` 已接受但根视图尚未更新时提前完成。
- 旧大厅关闭完成回调与退出恢复事务竞争，导致新登录会话加载历史游戏 URL。
- 退出恢复尚未完成就展示一键登录或创建 H5 登录，导致登录界面按旧方向闪现。

## 14. 验收标准

- H5 在 Android 和 iOS 使用同一组参数和调用顺序。
- `changeGameUrl` 可独立立即调用。
- 纯关闭不可能加载历史 pending。
- 大厅进入游戏时只加载最后一次提交的 URL，且只加载一次。
- GameInfo、Header 与业务方向同步，不依赖旋转动画完成。
- 所有 H5 和原生覆盖层按当前实际方向布局。
- H5 不依赖私有方向通知。
- 悬浮球恢复显示时没有旧位置跳动。
- `packageMode` 在进程生命周期内不可变；退出游戏总是恢复该方向。
- 一键登录和 H5 登录只在退出恢复方向及布局稳定后展示。
- 既有登录、退出、悬浮球心跳、窗口复用和可信来源校验测试继续通过。

## 15. 参考资料

- [JSBridge 方法清单](../JSBridge方法清单_jsbridge.md)
- [安卓 H5 游戏包开发需求文档](../安卓H5游戏包开发需求文档_requirements.md)
- [初始化接口字段说明](初始化接口字段说明_initialization-api.md)
- [Android 实施计划](superpowers/plans/2026-11-12-大厅屏幕与延迟游戏加载_welfare-screen-deferred-game-load.md)
- [Android 方向和关闭事务](../android/app/src/main/java/com/qishimeng/game/MainActivity.kt)
- [Android pending 与游戏导航](../android/app/src/main/java/com/qishimeng/game/web/WebContainer.kt)
- [Android Bridge 参数解析](../android/app/src/main/java/com/qishimeng/game/bridge/BridgeActions.kt)
- [Android 悬浮球显示前定位](../android/app/src/main/java/com/qishimeng/game/floatball/FloatingBallView.kt)
- [iOS 开发指南](../ios/开发指南_development.md)
- [iOS 悬浮球需求与实现](iOS悬浮球需求与实现_ios-floating-ball.md)
