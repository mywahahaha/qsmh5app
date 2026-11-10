# iOS悬浮球需求与实现

日期：2026-11-02；游戏 29，Bundle ID `com.un74meng.yjhcmc`，版本 `1.0.1`，构建 `3`。Android 本轮仅阅读源码；iOS 的真机安装由用户自行验证。

## 需求依据

- [共用游戏需求](../安卓H5游戏包开发需求文档_requirements.md) 的悬浮球及运营心跳章节。
- [初始化字段](初始化接口字段说明_initialization-api.md) 和 [JSBridge 清单 §3.4 / §8](../JSBridge方法清单_jsbridge.md)。
- Android `FloatingBallController.kt`、`FloatingBallView.kt`、`InitConfigParser.kt`、`PopupHeartbeatSession.kt`、`PopupHeartbeatService.kt`、`MainActivity.kt` 的当前源码。
- iOS 安全区域依据 [原生 UI 规范 §10](iOS原生UI设计规范_ios-native-ui-design.md)。

## Android 规则与 iOS 对应

| 规则 | iOS 实现 |
| --- | --- |
| 游戏未就绪不显示 | 需要有效登录 Token、游戏阶段和 `web_game` 主文档提交；协议和登录页不显示 |
| 初始状态 | `floatball.show`，缺省 false；active 初始 false |
| 最新运行状态 | Bridge 与成功心跳按实际处理顺序更新 show/active，最后有效状态生效 |
| 福利置顶 | 强制临时隐藏；更新 show/active 仍保存，关闭福利后恢复最新状态 |
| 游戏重新置顶 | 福利保留后方时允许球恢复，游戏与福利 WebView 实例复用 |
| 点击 | 打开或置顶 `web_welfare`；只有当前可见球接受点击 |
| 拖动 | 仅改变 y，范围 `[0, H-h]`；纵向最大移动达到 8pt 不触发点击 |
| 尺寸 | 正整数 dp 映射 pt，不按屏幕宽度缩放；限制在容器内 |
| 水平位置 | left 优先，缺省 right=0；偏移沿用 `值 × W / 360` |
| 垂直位置 | top 优先（含 0），完全忽略 bottom 的类型及数值；两者缺失 top=0 |
| 垂直单位 | `(0,1)` 为完整容器高度比例；0 或 ≥1 直接为 pt；bottom 从球下边缘算 |
| 布局更新 | 坐标 clamp 后向下取整；普通显隐不重置拖动位置，容器尺寸变化按配置重算 |
| 图片 | 默认/激活两张初始化图片，等比例显示；同 URL/状态不重复请求，已成功图片在会话中缓存；GIF 首帧 |
| 会话退出 | logout、关闭游戏、重新初始化重置 show 与 active、停止心跳，旧响应不再生效 |

iOS 的悬浮容器**水平覆盖整个窗口**，右侧 `right: 0` 贴屏幕右边，左侧 `left: 0` 贴屏幕左边；**纵向继续使用 safeArea**，避免系统顶部和底部操作区。Android 当前为全屏悬浮层；两端在各自容器内用相同公式。iOS 不再额外增加水平安全区边距。触摸阈值 Android 当前为 8px，iOS 为 8pt，以逻辑触摸单位适配。

## 心跳规则与本轮边界

`popupInterval` 需要有效 HTTPS URL、正整数秒数 interval；Debug-Local 仅允许指定本地 HTTP 来源。游戏就绪且球第一次实际显示后启动，等待完整 interval，POST `{}` 并携带当前统一 Header。请求完成后才等待下一周期，不并发、不补发。

成功响应示例：

```json
{"code":0,"data":{"floating_ball":{"show":false,"active":true}}}
```

隐藏球或福利遮挡不能停止已启动的心跳，否则无法被后续响应恢复。无状态字段、非法布尔值、HTTP/业务/解析失败均保留原状态，下一周期继续。退出和关闭游戏取消计时、使在途结果失效；iOS 退后台暂停，回前台重新等待完整周期，已启动会话即使球隐藏也能恢复轮询。

**本轮仅消费 `floating_ball`，没有实现运营窗口清单 `popup_pages`。** 不会因为收到该字段而新建或清理运营弹窗。角色、常亮、原生更新等亦不属于本次悬浮球开发。

## H5 是否需要区分客户端

悬浮球使用相同调用，不需要区分 Android/iOS：

```js
QSMH5APP.setFloatingBall({show: true, active: false}, function (result) {
  if (result.code !== 0) return; // 参数错误或原生失败
  if (result.data === false) return; // 未配置 floatball
  // 已保存运行态；福利置顶时仍会临时隐藏。
});
```

尺寸、位置、心跳由原生处理，H5 不需要根据机型换算坐标，也不应另外绘制一套悬浮球。完整能力尚不等价：角色和按 Android 包名打开应用仍未接入 iOS，隐私撤回为 iOS 当前专属；导航来源限制等沿用 JSBridge 清单 §8。

## 代码位置

- [FloatingBall.swift](../ios/Core/FloatingBall.swift)：配置校验、状态机和布局公式。
- [FloatingBallHeartbeat.swift](../ios/Core/FloatingBallHeartbeat.swift)：串行计时、前后台暂停及旧响应隔离。
- [AppCoordinator.swift](../ios/Core/AppCoordinator.swift)：游戏就绪、窗口栈、Bridge 与心跳状态合并。
- [FloatingBallView.swift](../ios/Features/FloatingBallView.swift)：图片、纵向拖动、点击和空白区域透传。
- [RootViewController.swift](../ios/App/RootViewController.swift)：独立悬浮层，水平全窗口、纵向安全区域。
- [WebPageController.swift](../ios/Features/WebPageController.swift)：主文档可见通知；普通置顶不导航，显式 `changeGameUrl` 才加载地址。

窗口复用同时修正一处问题：游戏已切到新地址后，普通 `openMiniWindow` 置顶不能又加载初始化 URL。福利返回也保留游戏原文档及 JS 状态。

## 验证记录

核心 XCTest 51 项通过，含新增 14 项悬浮球和心跳用例。首次证据：`ios/artifacts/test-run.l2e9UQ/Tests.xcresult`，构建号更新后复核仍 51 项通过：`ios/artifacts/test-run.tyFGXR/Tests.xcresult`。覆盖字段优先级、比例/绝对位置和边界、游戏就绪、福利前后顺序、最近状态、退出重置、图片去重、HTTP 六 Header、完整间隔、隐藏后继续轮询、后台恢复、网络失败及晚响应隔离。

完整模拟器 XCUITest 10 项通过（新增悬浮球 3 项、原有流程 7 项），0 失败；证据：`ios/artifacts/test-run.gADywd/Tests.xcresult`。验证登录前无球、纵向拖动与边界、不误触福利、图标请求去重、福利覆盖后最新态、游戏换地址后置顶、初始隐藏可启用、退出重置及真实本地 HTTP 心跳六 Header。隐私、退出、Token 持久化、错误参数、导航拦截等旧流程也通过。

该轮后增加“游戏换地址后再打开/关闭福利仍保留新文档”的专项断言，复核通过：`ios/artifacts/test-run.tyFGXR/Tests.xcresult`。初次应用截图横屏裁剪异常，改为系统全屏截图后该专项再次通过：`ios/artifacts/test-run.N4SxL5/Tests.xcresult`。三张截图已导出至新交付目录 `全屏截图/` 并人工检查，分别为默认位置、福利置顶无球、激活球及返回后保留新文档。业务代码与已签名包未因此改变。不以替身测试代替真实游戏、iPhone 安装或运营商认证验收。

## 签名母包

- 路径：`ios/artifacts/交付_20261102_悬浮球_AdHoc/全民红月_1.0.1_3_母包_AdHoc.ipa`。
- SHA-256：`4f0b121e9d26c4b97db92360eaf2b4d4c0af98cc13a900d63f51ef6849a40b01`。
- Release arm64 归档及 Ad Hoc 导出成功；最终 IPA 已解包，严格验签及证书/profile/权限、Bundle ID、构建号、方向、SDK 资源和生产隔离检查通过。
- 母包渠道为空，沿用已有分包脚本，用此构建 3 母包重新分包后安装。旧包仅新增渠道目录不能获得新代码。
- 当前描述文件仅允许登记的 6 台设备安装，未连接 iPhone；真实账号、游戏、运营商取号及最低系统仍待设备验证。构建摘要、SHA256、安装与分包说明在同一交付目录。

## 本地复现

```bash
QSM_SIMULATOR_ID=实际设备UUID ios/Scripts/run_tests.sh
```

新增本地场景 `floatball`、`floatball-edge`、`floatball-hidden`、`floatball-heartbeat`；只供 Debug-Local，生产包无本地入口。`floatball-edge` 返回 `right: 0`，用于检查球体是否贴屏幕右边；其他场景用于显隐、拖动与福利复用，心跳场景每两秒轮询，首次响应隐藏并激活，后续响应恢复激活状态。

## 2026-11-04：`right: 0` 贴边修复

真机截图显示横屏右侧悬浮球离屏幕边缘还有较大距离。根因是旧容器整体使用 `safeAreaLayoutGuide.layoutFrame`，横屏设备的右侧安全区先缩小容器；布局公式虽然算出容器内 `right=0`，仍只能贴到**安全区**右边。

现在悬浮层水平使用根视图完整宽度，纵向仍保持原安全区起点与高度。原有 `left/right` 优先级、360 宽度基准、`top/bottom` 和纵向拖动逻辑不变。对于 `right: 0`，球体 `maxX` 等于屏幕 `maxX`；非零偏移也从实际屏幕边缘计算。游戏 H5 即使因自身画面比例出现黑边，也不影响原生悬浮球的屏幕边缘定位。

- 新增 `floatball-edge` UI 用例通过，直接断言球体右边等于应用窗口右边；系统横屏截图已导出并检查：`ios/artifacts/悬浮球贴边截图_20261104/33CAD9E3-F79C-47DD-8CC6-3D4A17E80625.png`。
- 既有悬浮球单元测试 10 项通过，纵向拖动、福利覆盖和返回的 UI 用例复核通过：`ios/artifacts/test-run.OO6I7H/Tests.xcresult`。
- 使用游戏 92“天尊传奇”的现有图标、横版闪图、Bundle ID `com.un74meng.tzcq` 和最新签名描述文件，生成版本 1.0.1、构建 3 的 Ad Hoc IPA：`ios/artifacts/交付_20261104_天尊传奇_悬浮球贴边_build3/92_com.un74meng.tzcq_1.0.1_3_AdHoc.ipa`。最终严格验签通过；最低系统 iOS 14.0，空渠道包无 `packCode` 目录。SHA-256：`4ffb6192dabfd35d11a73e4c9c1db774a6936b5adc074dcaad89f8253ad084f8`。

截图是本地替身页面的几何验证，真实游戏画面与设备方向仍需用户安装构建 3 后复验。用户保存的游戏 92 构建 2 配置和产物均未覆盖。
