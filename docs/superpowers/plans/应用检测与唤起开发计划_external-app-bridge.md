# 应用检测与唤起开发计划

**目标：** 新增 `isAppInstalled({packageName}, callback)` 和 `openApp({packageName}, callback)`，供可信 H5 页面检测和打开安卓应用。

**实现：** 沿用 `QSMH5APP`、统一 `Result` 和现有异步消息回调；不另建协议。检测成功以 `data` 返回布尔值；打开成功表示系统接受启动请求，不代表第三方业务执行成功。

**约束：** 保留现有未提交文件，在当前功能分支增量修改，不自动提交。Android 11+ 声明 MAIN/LAUNCHER 查询及 `com.me74.qsmhz`，不使用 QUERY_ALL_PACKAGES。无桌面入口的其他包须显式配置 queries，否则无法保证检测准确。

## 接口与边界

- `packageName` 必须为合法、非空字符串；无效参数返回 `code:1001,data:false`。
- `isAppInstalled`：可见且已安装为 `code:0,data:true`；未安装或系统不可见为 `code:0,data:false`。
- `openApp`：使用系统提供的包启动 Intent，不接受自定义组件、URI 或 Intent 参数；未安装、无入口、系统拒绝为 `code:0,data:false`。
- 不自动下载或跳转应用商店；由 H5 点击按钮触发打开，原生不能验证用户手势，不在加载时自动唤起。
- 非预期异常返回 `code:9999,data:false`，不冒充未安装。

## 执行清单

- [x] 1. 为注入脚本和真实 WebView 添加接口回调、并发隔离、缺包及参数错误测试，运行并观察新测试失败。
- [x] 2. 新增包管理封装，接入 BridgeActions、MainActivity 和 Manifest；添加 Android 包查询及启动 Intent 测试，验证由失败转为通过。
- [x] 3. 补充 Promise 封装、Vue2 按钮示例和方法清单；执行 JS、安卓单元及设备测试，进行独立只读代码审查。

## 重点核验

1. 并发包查询不能串用回调，回调只交付一次。
2. 缺少包名、数字、空白、带空格/URI 的参数不能触发系统操作。
3. 打开失败不能回传 true，启动 Intent 必须绑定传入包且支持 Activity 以外 Context。
4. 保留统一回调结构；Promise 包装直接 resolve 布尔值，非零 code 必须 reject。
5. 验证 Android 11+ 的 Manifest 可见性配置，不宣称能够检测任意隐藏包。

## 执行记录

- 基线：现有 JS 测试 4/4 通过；安卓单元测试进行中。
- 用户已要求可行时直接开发，采用当前会话执行，不停顿等待计划确认。
- 实现后首轮验证：JS 7/7、安卓单元 80/80、设备专项 6/6 通过；完整设备回归 77 项（4 项条件跳过）、Release 构建通过。Python 出包工具 20/20、渠道工具 2/2 通过。
- 独立只读审查发现：外层非对象参数可能使 Map 解码失败、回调丢失；包名段首下划线被误接受。新增两项设备回归均已观察到失败，修复仅限新增应用接口的参数解析与校验，不改变其他旧接口。
- 目标包 `com.me74.qsmhz` 未安装于测试设备，不宣称该游戏实际唤起已验收。无自动提交、推送或发布。
- 修复后两次全量回归均在既有测试 HTTP 夹具的响应写入处因 `SocketException: Broken pipe` 中止；首轮显示 `phoneCancellationBeforeJavascriptDeliveryWaitsForCloseThenRestoresLogin`，第二轮进程直接崩溃。根因为页面关闭时连接提前断开而服务器线程未捕获该正常网络异常。仅为单个测试连接捕获 `SocketException` 后继续接受下一次请求，不吞掉断言或其他异常，不改产品逻辑。
- 最终验证：`node --test 示例/bridge-script.test.js 示例/qsm-jsbridge.test.js 示例/external-app-bridge.test.js` 为 7/7；`:app:testDebugUnitTest` 为 80/80；`:app:connectedDebugAndroidTest` 的 XML 报告共 74 项，70 项通过、4 项条件跳过、0 失败（Gradle 控制台结束时显示 78，按 XML 的真实 74 个 testcase 计数）；`:app:assembleRelease` 成功。日志：`android/external-app-complete-verification.log`。格式整理后另执行测试源码编译、安卓单元测试、Release 构建成功，日志：`android/external-app-final-build.log`。
- 保留原有 AGP/compileSdk、弃用 API 与阿里云 AAR 的构建警告，不在本功能内升级依赖；Release 输出为未签名模板 APK，不是可发布的已签名业务包。
