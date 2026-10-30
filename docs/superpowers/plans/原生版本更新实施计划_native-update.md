# 原生版本更新实施计划

> 实施方式：在当前会话按 executing-plans 技能逐项实施，先写失败测试，再实现及验证；最后进行独立代码审查。Android 工程目前为未跟踪文件，直接使用当前目录，避免新工作树遗漏现有源码；不自动提交其他工作。

**目标：** 按 2026-10-28 用户提供的效果图、`bg.png` 和新 `updateInfo` 契约，实现原生更新弹窗、外部浏览器更新、内部下载及系统安装，同步接口文档。

**架构：** `UpdatePolicy` 校验配置；原生 `UpdateDialog` 负责视图与进度；`UpdateService` 管理更新状态与启动放行；独立下载器和安装器处理 HTTP、私有文件、FileProvider 与系统安装授权。`MainActivity` 在隐私流程结束、登录前接入，每次启动只检查一次。

**技术栈：** Kotlin、Android 原生 View/Dialog/Canvas、HttpURLConnection、AndroidX FileProvider、JUnit、Android 仪器测试；沿用现有依赖。

**需求来源：** 本会话 2026-10-28 的更新功能要求和上传的参考图。接口文档同步到《安卓H5游戏包开发需求文档》第 4.1、4.3 和第 8 节，并新增初始化接口字段文档。

## 完整实现约定

- `open=0` 不校验其他更新字段、不弹窗；缺失更新配置时跳过；`open=1` 展示更新。新契约取代 `isUpdate/forceUpdate/downloadUrl/isPop`。
- `width` 是可用窗口宽度的百分比；`height="auto"` 由内容决定，数字或数字字符串是可用窗口高度的百分比。兼容百分号字符串；有效范围为大于 0、至多 100。超过屏幕的内容可滚动，保留关闭按钮的可见空间。
- 使用上传的 `bg.png` 作为随包原生头图，按原图比例缩放。白色面板圆角 15 个界面逻辑像素（Android dp），头部白色标题来自 `title`，红橙色说明来自 `desc`，版本及更新时间分别来自 `version/time`；可选文案为空时隐藏相应行。
- 居中显示面板，面板外遮罩为黑色、透明度 45%；绿色圆角主按钮“立即更新”。`force=0` 显示面板外下方的圆形关闭按钮并允许返回键关闭；点击遮罩不关闭，防止误触；`force=1` 隐藏关闭按钮、拦截返回键。
- `popup=1` 点击“立即更新”后通过外部浏览器打开绝对 HTTP/HTTPS `url`；非强制更新打开后继续登录，强制更新保留窗口，回到游戏仍可点击更新。
- `popup=0` 点击后在应用内下载绝对 HTTP/HTTPS `url`，显示蓝绿渐变进度及百分比；长度未知时显示不确定进度，完成后再显示 100%。下载中禁止重复发起。
- 下载完成后保留进度条，并在下方显示绿色同款“立即安装”按钮，同时自动请求系统安装。用户取消安装时按钮保留，可再次安装。
- Android 8+ 未授权安装未知来源应用时先打开本应用安装授权页，返回且获得授权后继续安装；没有授权时保留按钮和提示。系统确认安装由 Android 完成，不做静默安装。
- 正常强制更新保持阻断；配置、下载、安装调用异常沿用现有需求：简短提示、结束本次更新、继续登录。用户关闭非强制更新时中止本次下载；Activity 销毁释放网络及回调。
- 更新 URL 不套用游戏 H5 跳转域名列表，按更新契约独立校验 HTTP/HTTPS；私有更新目录只通过 FileProvider 临时读权限交给安装器，不申请广泛存储权限。
- 保留原有隐私、Token 登录、阿里云及 H5 登录顺序；示例中 `open=0` 的空文案配置不会影响启动。

## 文件职责

| 文件 | 职责 |
| --- | --- |
| `android/app/src/main/java/com/qishimeng/game/update/UpdatePolicy.kt` | 新字段、显示尺寸与更新模式校验 |
| `android/app/src/main/java/com/qishimeng/game/update/UpdateService.kt` | 更新状态、错误及一次性启动放行 |
| `android/app/src/main/java/com/qishimeng/game/update/UpdateDialog.kt` | 原生窗口、关闭控件、文字、进度、安装按钮 |
| `android/app/src/main/java/com/qishimeng/game/update/ApkDownloader.kt` | 后台 HTTP 下载、进度、重定向、取消、临时文件 |
| `android/app/src/main/java/com/qishimeng/game/update/ApkInstaller.kt` | 安装授权、FileProvider URI 与系统安装 Intent |
| `android/app/src/main/java/com/qishimeng/game/MainActivity.kt` | 隐私后、登录前的更新接线及生命周期 |
| `android/app/src/main/AndroidManifest.xml`、`res/xml/update_file_paths.xml` | 安装权限与仅更新目录的 FileProvider |
| `android/app/src/main/res/drawable-nodpi/update_header.png` | 用户上传的头部背景原图 |
| `android/app/src/test/java/com/qishimeng/game/update/` | 配置、更新流程、下载回归测试 |
| `android/app/src/androidTest/java/com/qishimeng/game/update/` | 原生窗口、启动顺序、下载/安装调用与截图验证 |

## 审查重点

1. 强制更新在正常状态不可绕过；异常仍按既有契约放行，且不能重复登录。
2. 固定高度、横竖屏、小屏及长文案保持按钮可达；截图检查头图、遮罩及圆角。
3. 下载断网、HTTP 错误、长度未知、取消、重复点击、Activity 销毁不得误触发安装或留下半包。
4. Android 8+ 授权页返回、拒绝授权、取消安装可再次点击安装，URI 必须能被系统读取。
5. 隐私未处理前不下载、不弹更新；关闭或异常后登录流程只启动一次。

## 任务 1：新字段与更新状态

**接口：** `UpdatePolicy.evaluate(fields: Map<String, Any?>): UpdateDecision` 返回跳过、有效 `UpdateInfo` 或可放行错误；`UpdateInfo` 包含 width/height/popup/force/title/time/desc/version/url。`UpdateService` 通过注入的下载、安装、外链和视图回调管理状态。

- [x] 写策略及状态机测试：`open=0` 忽略坏字段、缺配置跳过、新字段/尺寸/URL 校验、外链分支、下载后自动安装及再次安装、非强制关闭、强制返回拦截、异常一次性放行。
- [x] 执行 `./gradlew.bat testDebugUnitTest --tests '*UpdatePolicyTest' --tests '*UpdateServiceTest'`，确认旧实现无法通过新契约。
- [x] 最小实现新字段及状态；运行相同测试确认通过。

## 任务 2：原生窗口及资源

**接口：** `UpdateDialog` 接收 `UpdateInfo` 与操作回调，并由状态更新展示下载进度、安装按钮和错误；Android 原生布局按可用窗口百分比计算。

- [x] 写仪器测试：普通/强制、width/height/auto、空文案、长内容滚动、进度及“立即安装”按钮。
- [x] 运行指定测试确认缺少窗口时失败。
- [x] 加入原图，实现原生布局、渐变进度、圆角与 45% 遮罩；运行测试并保存截图进行视觉核对。

## 任务 3：下载与安装

**接口：** `ApkDownloader` 下载到应用私有更新目录并报告已下载字节/总长度，支持取消；`ApkInstaller` 校验 APK 包名、请求授权并发出带临时读权限的安装 Intent。

- [x] 写本地 HTTP 下载测试覆盖成功、HTTP 失败、重定向、未知长度、截断与取消；仪器测试覆盖 content URI 可读、授权分支及重复安装。
- [x] 确认测试失败后实现后台下载、临时文件替换、FileProvider/安装权限和系统安装调用。
- [x] 运行目标测试确认通过；测试只拦截安装调用，不自动确认安装真实游戏。

## 任务 4：启动接线与文档同步

**接口：** `MainActivity` 隐私同意/已同意后调用 `beginUpdateFlow()`；更新放行后调用既有 `beginLoginFlow()`；`onResume/onDestroy` 转发给更新器。

- [x] 写真实 Activity 启动测试，验证 `open=0`、隐私顺序、关闭后登录、强制更新、下载完成自动请求安装与再次安装。
- [x] 确认失败后接入流程；同步需求示例、更新章节、初始化接口字段文档、导航、联调指南及旧开发计划相关字段。
- [x] 执行 Android 全量单元测试、设备测试与 Debug/Release 构建；检查文档旧字段残留与界面截图。
- [x] 独立审查本次文件；处理重要问题并回归，记录验证边界。

## 进度记录

- 2026-10-28：完成现状梳理。原更新策略仍为旧字段，`MainActivity` 尚未调用更新服务，原 Manifest 未配置安装权限及 FileProvider。用户需求和现有容错规则形成以上完整约定，用户已确认并在当前会话直接实施。

- 2026-10-28：四项实施任务完成；80 项单元测试、12 项更新设备专项及 Debug/Release 构建通过；当前源码全量设备报告 62 项通过、4 项按配置跳过、无失败。独立审查提出的长标题裁切已完成红→绿回归，截图及真机验证边界见联调指南。
