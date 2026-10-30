# 退出登录重走认证流程实施计划

**目标：** 按本次用户要求，`logout` 关闭游戏/大厅等当前会话页面，清除持久化 `accountToken` 后，从一键登录分支重新开始；开启一键登录则尝试认证，未开启/取消/失败则进入新的 H5 登录页。

**实现：** 沿用 `BridgeActions.logout` 的 Token 删除、MainActivity 的页面关闭与悬浮球隐藏，改为调用既有 `beginOnekeyOrH5Login()`。不重跑初始化、更新检查或旧 Token 自动登录；不清除业务通用存储和隐私同意状态。

**约束：** 在现有未提交工程增量修改，不提交、不发布；不新增 Bridge 方法或改返回结构。测试保留真实 MainActivity、WebView、OnekeyLoginService、加密 Token 存储及兑换解析，仅替换运营商 SDK 网关和 HTTP 传输边界，防止真实取号或线上请求。

## 任务

- [x] 1. 保留退出关闭页面的设备基线。为 MainActivity 提取已有的运营商网关依赖为私有字段（仅内部复用，不增加测试专用公开 API），补充开启配置后退出重走认证、成功重建游戏、取消/失败进入 H5、未开启跳过认证的设备测试，运行确认旧分支失败。
- [x] 2. `onLogout` 改为 `beginOnekeyOrH5Login()`；验证 SDK 调用前旧 Token 已删除且旧窗口已关闭，退出不再执行 Token 自动登录，登录成功持久化新 Token，失败不保留旧 Token；保留旧页面后续 Bridge 失效。
- [x] 3. 同步需求、Bridge 清单、一键登录专项及联调指南。运行 JS、安卓单元、设备回归与 Release 构建，独立只读审查本次改动。

## 核验重点

- 有效开启配置必须真正触发运营商网关，不是直接显示 H5。
- 一键登录成功保存新 Token 并创建新游戏 WebView；取消、失败、兑换失败仅创建新 H5 登录页。
- 退出从认证步骤开始，不发旧 Token 登录请求、不重跑初始化和更新。
- 无配置/关闭配置沿用 H5 登录；页面关闭、悬浮球隐藏、旧 Bridge 隔离、通用存储保留不能退化。
- 避免真实运营商取号与生产账号登录；真实运营商环境由后续联调验收。

## 进展

- 已定位 `onLogout` 直接 `openH5LoginPage()` 与新要求不符；历史文档也明确旧规则，本次同步更新。
- 基线：安卓单元测试通过，既有“退出创建新 H5 登录页/重新登录新建游戏”设备用例通过。
- 回归先失败：`logoutRestartsOnekeyAfterClearingSessionAndSuccessCreatesNewGame` 在旧分支因认证网关未被调用而失败，见 `android/build/logout-red.log`；入口修正后 `MainActivityFloatingBallTest` 共 14 项通过，见 `android/build/logout-green.log`。
- 最终验证：JS 7/7、安卓单元 80/80、Python 出包工具 20/20、渠道工具 2/2 通过。设备全量 XML 共 81 项，77 项通过、4 项条件跳过、0 失败。`:app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleRelease` 成功，日志 `android/build/logout-full-verification.log`；保留既有 AGP/compileSdk 与弃用 API 警告。Release 为未签名模板 APK，不是正式签名发布包；未提交或发布。
- 独立只读审查：未发现本次引入的 Critical、Important 或 Minor。明确保留历史限制：若游戏等窗口仍存在时先主动发起另一笔 `requestPhoneLogin`，再退出，退出前已在途的认证/兑换结果尚未通过会话代次失效；该跨会话认证取消能力不在本次入口调整内，未宣称已解决。标准单次退出后重新认证已覆盖。
