# 代码与文档一致性审查

## 后续进度更新（2026-11-02）

- 用户在审查后确认当前 Android 项目已完成真机测试；[联调指南](安卓工程构建与联调指南_android-build-and-integration.md)、文档导航及相关需求说明已同步该进度。
- 下文“本轮未执行”“未在设备验证”等表述记录的是审查时由审查者执行的验证方式，不再作为项目当前“尚未真机测试”的进度结论。本次未补录设备型号、版本、逐项日志或新的自动化测试数量。
- 第 15 项涉及的渠道读取器源码路径、主流程接线及真机测试进度说明已同步；该项中的历史实施计划描述尚未调整。下方文档行号为审查时位置，后续编辑可能使行号偏移。
- 本次仅更新文档进度，第 1—14 项尚未修复；已完成真机测试不自动关闭这些实现或契约问题。

## 审查范围与基线

- 日期：2026-11-02。
- 基线：master，提交 f304554，并包含审查开始前已有的游戏常亮相关未提交修改。
- 范围：Android 初始化、隐私与登录、Token、WebView/JSBridge、角色和通用存储、应用检测、心跳与窗口、版本更新，以及 Windows 出包器、渠道分包脚本、H5 示例和对应文档。
- 按 receiving-code-review 技能要求核对实际实现与调用链。本轮只新增审查报告，没有修改业务实现或原有需求文档。
- 排除文档明确列出的真机、真实服务端、运营商认证、系统安装器及桌面 GUI 手工验收本身。下面涉及这些模块的条目，均有独立的代码证据，不以“尚未真机测试”作为缺陷依据。
- 未访问线上业务接口，未读取签名密码或证书内容，未提交或推送。

## 结论

共整理 15 项不一致：1 项 P1、12 项 P2、2 项 P3。P1 优先处理会话隔离；P2 包括实现缺口和需要统一的文档契约；P3 是进度展示及陈旧说明。

“本地复现”指 JVM、Python 或内存替身诊断，不代表设备复现。“静态确认”指实现分支或所用 API 的语义可以确认，完整设备表现尚未实测。

## P1：优先处理

### 1. 退出登录后，旧的一键登录兑换仍能重新写入 Token

- **文档约定：** [Bridge 清单第 161 行](E:/Yang/youxing_item/qishimeng_h5_sdk/JSBridge方法清单_jsbridge.md:161)要求旧回调不能重新打开游戏或修改新会话；[一键登录需求第 231 行](E:/Yang/youxing_item/qishimeng_h5_sdk/docs/阿里云一键登录独立需求_aliyun-onekey-login.md:231)要求忽略已销毁或取消流程的回调。
- **实际实现：** [OnekeyLoginService 第 63 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/auth/OnekeyLoginService.kt:63)将兑换任务放入执行器；第 77—84 行无会话代次检查，兑换完成即保存 Token。[退出流程第 421 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/MainActivity.kt:421)没有取消旧兑换任务。[BridgeActions 第 44 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/bridge/BridgeActions.kt:44)仍允许旧结果触发打开游戏。
- **触发条件与影响：** 游戏或福利页发起一键登录，取得运营商凭证后业务兑换尚未结束，此时退出登录。旧兑换随后返回，可能恢复已经清除的 Token，或者覆盖新登录的 Token，并重新触发游戏开页。
- **验证：** 内存 HttpTransport 与排队 Executor 复现“兑换排队 → 清除 Token → 执行旧任务”，结果为 Success，存储重新变为旧任务返回的 Token。页面销毁后的某些入口有 isDestroyed 检查，但 Token 写入发生在它之前，不能保护存储。
- **建议：** 以会话代次约束兑换、Token 写入和界面回调；退出、重新登录或 Activity 销毁时令旧代次失效。

## P2：实现与契约差异

### 2. 非法心跳百分比通过解析，在 UI 应用阶段抛异常

- **文档约定：** [需求第 372 行](E:/Yang/youxing_item/qishimeng_h5_sdk/安卓H5游戏包开发需求文档_requirements.md:372)要求格式失败保持当前状态，下一周期继续。
- **实际实现：** [HeartbeatResponseParser 第 67 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/network/HeartbeatResponseParser.kt:67)直接调用 toFloat，接受 NaN%；[WindowLayout 第 33 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/web/WindowLayout.kt:33)随后对 NaN 调用 roundToInt。[主线程分发第 262 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/MainActivity.kt:262)及 [PopupHeartbeatSession 第 23 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/network/PopupHeartbeatSession.kt:23)没有保护应用阶段异常。
- **验证：** 本地调用现有编译类，width 为 NaN% 的运营窗口成功解析为 Percent(NaN)，布局解析抛出 IllegalArgumentException: Cannot round NaN value。UI 线程未捕获异常的路径由代码确认，未在设备上复现崩溃。
- **影响与建议：** 异常响应不能按约定被忽略；悬浮球状态还可能在应用弹窗失败前已被更新。应在解析时校验数值有限且合法，并在改变当前状态前验证响应。

### 3. 已关闭的一键登录功能，其坏配置仍可使全局初始化失败

- **文档约定：** [一键登录字段第 97 行](E:/Yang/youxing_item/qishimeng_h5_sdk/docs/阿里云一键登录独立需求_aliyun-onekey-login.md:97)及第 221—222 行要求 open=0 跳过认证，不可用配置回退 H5。
- **实际实现：** [InitConfigParser 第 18 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/config/InitConfigParser.kt:18)无条件解析 config、ui 和协议项，第 58—66 行继续解析认证参数；任一类型异常在第 69—70 行变成整体 Failure。[MainActivity 第 196 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/MainActivity.kt:196)因此直接显示初始化失败，无法进入 H5 回退。
- **验证：** 本地现有编译类诊断确认，open=0 时 config.ui 为数组、screen_code 为字符串 "1"、app_protocols 为 [{}]，分别导致 Failure；省略 config 则为 Success。
- **建议：** 先处理开关；将可选认证配置的不可用状态与全局初始化错误隔离。

### 4. 登录响应缺少 code 字段仍被当成成功

- **文档约定：** [一键登录需求第 148 行](E:/Yang/youxing_item/qishimeng_h5_sdk/docs/阿里云一键登录独立需求_aliyun-onekey-login.md:148)及第 155—158 行要求明确的 code=0 和有效 Token。
- **实际实现：** [ApiModels 第 16 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/network/ApiModels.kt:16)将 code 定义为 Int；[ApiClient 第 19 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/network/ApiClient.kt:19)直接用 Gson 解码，缺失字段成为 0。[TokenLoginService 第 26 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/auth/TokenLoginService.kt:26)与一键兑换服务均据此判断成功。
- **验证：** 内存响应仅含 data.token、不含 code，TokenLoginService 仍返回 Success 并保存 Token。
- **建议：** 成功前验证 code 字段存在且类型正确，避免以基本类型默认值代替接口成功状态。

### 5. Bridge 非对象参数解码失败后丢失原请求回调

- **文档约定：** [Bridge 清单第 338 行](E:/Yang/youxing_item/qishimeng_h5_sdk/JSBridge方法清单_jsbridge.md:338)要求 getLocalStorage 参数错误返回 code:1001、data:""。
- **实际实现：** [BridgeJavascriptInterface 第 33 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/bridge/BridgeJavascriptInterface.kt:33)把多数方法参数直接解码为 Map。getLocalStorage(123, callback) 等非对象参数在进入业务校验前抛异常，第 43 行用 id=null 返回错误；[BridgeScript 第 9 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/bridge/BridgeScript.kt:9)找不到该回调即丢弃。
- **影响：** H5 收不到约定的参数错误，示例 Promise 包装可能一直 pending；其他采用同样 Map 解码的方法也存在这一机制。
- **验证与建议：** 静态确认。应在解码失败时保留已读取的请求 ID，并按方法返回对应错误数据；应用检测已有保留无效参数类型的处理，可统一入口行为。

### 6. 一次非 GET 导航会抑制后续独立 GET 的自动重试

- **文档约定：** [联调指南第 119 行](E:/Yang/youxing_item/qishimeng_h5_sdk/docs/安卓工程构建与联调指南_android-build-and-integration.md:119)及第 133 行规定明确 GET 可有限重试，非 GET 不自动重放。
- **实际实现：** [WebPageView 第 127 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/web/WebPageView.kt:127)及第 133 行把 replaySafe 设为 false；只有原生 load 第 86 行恢复它。页面内后续明确 GET 虽在第 105—108 行启动新的恢复流程，却没有重置该标记。第 166 行仍将错误判为不可重试。
- **触发条件：** POST 主文档成功显示后，在同一 WebView 中进入一个独立 GET 页面；后者遇到可重试的网络或 HTTP 错误。
- **影响、验证与建议：** 后续 GET 首次失败即进入最终错误。静态调用链确认；应让重放安全状态随每次独立导航更新，并继续保护同一次 POST 导航及其重定向。

### 7. H5 窗口百分比尺寸和居中坐标不会随视口变化重算

- **文档约定：** [需求第 234 行](E:/Yang/youxing_item/qishimeng_h5_sdk/安卓H5游戏包开发需求文档_requirements.md:234)及第 295 行要求尺寸与定位基于当前方向下的可用视口。
- **实际实现：** [WebContainer 第 239 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/web/WebContainer.kt:239)只在创建窗口时计算固定像素 LayoutParams，没有随 host 布局变化重新应用。Manifest 自行处理 orientation/screenSize，Activity 也未重算窗口。
- **触发条件：** 分屏、折叠或窗口尺寸变化；横屏应用打开外部浏览器时，[MainActivity 第 538 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/MainActivity.kt:538)会临时切竖屏，此时新建的窗口也可能在恢复横屏后保留旧尺寸。
- **验证与建议：** 静态确认；具体设备视觉结果未实测。应随容器可用视口变化重新解析各实例保存的 PageSpec。悬浮球已有自己的布局监听，不属于此问题。

### 8. 更新弹窗实际为配置百分比的 70%，文档仍按原百分比说明

- **文档约定：** [初始化字段第 47 行](E:/Yang/youxing_item/qishimeng_h5_sdk/docs/初始化接口字段说明_initialization-api.md:47)规定 width=50 表示视口宽度 50%；[需求第 432 行](E:/Yang/youxing_item/qishimeng_h5_sdk/安卓H5游戏包开发需求文档_requirements.md:432)规定圆角 15dp。
- **实际实现：** [UpdateDialog 第 150 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/update/UpdateDialog.kt:150)与第 182 行额外乘 DIALOG_SCALE=0.7，第 44 行圆角同样缩放。width=50 实际约为 35%；固定 height=60 在未受可用高度裁剪时约为 42%；圆角为 10.5dp。
- **验证：** 静态算式确认。[现有设备测试第 43 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/androidTest/java/com/qishimeng/game/update/UpdateDialogTest.kt:43)及第 80 行明确要求“整体缩小 30%”。本轮未执行这些设备测试。
- **建议：** 确认后续视觉调整的最终业务口径，再同步字段说明、主需求和测试。现有测试说明缩放可能是有意调整，不宜直接删除。

### 9. 渠道码格式允许换行，但其值随后被直接用于 HTTP Header

- **文档约定：** [分包文档第 36 行](E:/Yang/youxing_item/qishimeng_h5_sdk/安卓渠道分包设计文档_android_channel_packaging.md:36)仅限制 UTF-8、4096 字节和 NUL；[需求第 422 行](E:/Yang/youxing_item/qishimeng_h5_sdk/安卓H5游戏包开发需求文档_requirements.md:422)要求无效渠道码返回空串，不影响业务。
- **实际实现：** [channel_apk.py 第 118 行](E:/Yang/youxing_item/qishimeng_h5_sdk/tools/channel_apk.py:118)允许 CR/LF，[ChannelCodeReader 第 66 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/channel/ChannelCodeReader.kt:66)也未过滤；[GameHeaders 第 9 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/network/GameHeaders.kt:9)将原值送到 setRequestProperty。
- **验证：** 合成 APK 写入诊断确认带换行渠道码被完整保存；离线 JDK 17 setRequestProperty 对示例值抛 IllegalArgumentException，未发网络请求。Android 对应请求失败路径由代码确认，未在设备验证。
- **建议：** 在分包输入与 Android 读取边界统一定义适合 Header 的字符集，至少拒绝 CR/LF；读取到非法值时按约定返回空串。

### 10. 不同合法版本号会被清洗成同一输出名并覆盖成品

- **文档约定：** [需求第 406 行](E:/Yang/youxing_item/qishimeng_h5_sdk/安卓H5游戏包开发需求文档_requirements.md:406)及联调指南第 39 行规定按包名和游戏版本命名，没有说明版本清洗碰撞。
- **实际实现：** [validators.py 第 19 行](E:/Yang/youxing_item/qishimeng_h5_sdk/packager/validators.py:19)仅校验版本非空；[build_service.py 第 137 行](E:/Yang/youxing_item/qishimeng_h5_sdk/packager/build_service.py:137)把多种字符替换为下划线，第 125—126 行无冲突提示地替换现有 APK 与信息文档。
- **验证：** 本地调用文件名转换，1.0+1 与 1.0_1 均得到 1.0_1；最终覆盖由输出路径与 os.replace 调用确认。
- **建议：** 明确允许的版本字符并在输入时拒绝冲突，或采用可区分的编码；不同原始版本映射到同一成品路径时应提示。

### 11. 签名密码实际写入临时文件，与“仅保留在进程内”要求不符

- **文档约定：** [需求第 403 行](E:/Yang/youxing_item/qishimeng_h5_sdk/安卓H5游戏包开发需求文档_requirements.md:403)规定密码仅保留在当前进程内。
- **实际实现：** [build_service.py 第 48 行](E:/Yang/youxing_item/qishimeng_h5_sdk/packager/build_service.py:48)把密码明文写入临时 signing.properties，再将路径传给 Gradle；[联调指南第 37 行](E:/Yang/youxing_item/qishimeng_h5_sdk/docs/安卓工程构建与联调指南_android-build-and-integration.md:37)已如实说明临时文件方案。
- **验证：** 静态确认，无需读取实际密码。TemporaryDirectory 正常退出时清理，强制终止时存在残留可能；仅调用 chmod(0600) 不足以证明 Windows ACL 已限制为当前用户。
- **建议：** 统一需求与实现方案。如果保留临时文件，应明确其权限、生命周期和异常清理要求；如果坚持仅在内存保存，则调整 Gradle 凭据传递方式。本项不表示已发现密码被写入构建日志。

### 12. 出包器未生成文档要求的多密度图标

- **文档约定：** [需求第 405 行](E:/Yang/youxing_item/qishimeng_h5_sdk/安卓H5游戏包开发需求文档_requirements.md:405)要求转换成 Android 多密度资源。
- **实际实现：** [resource_mapper.py 第 43 行](E:/Yang/youxing_item/qishimeng_h5_sdk/packager/resource_mapper.py:43)只把输入 PNG 原样复制到 mipmap-nodpi/ic_launcher.png，没有生成 mdpi、hdpi、xhdpi 等尺寸。
- **验证与建议：** 静态确认。这不表示图标完全不能显示，但不满足转换约定。应补资源转换，或将实际采用单张 nodpi 图标的策略写回需求。

### 13. 角色保存无法按契约反馈磁盘持久化失败

- **文档约定：** [需求第 332 行](E:/Yang/youxing_item/qishimeng_h5_sdk/安卓H5游戏包开发需求文档_requirements.md:332)与 Bridge 清单第 197 行规定写入失败返回 false。
- **实际实现：** [SharedPreferencesRoleStorage 第 15 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/role/SharedPreferencesRoleStorage.kt:15)使用不返回磁盘写入结果的 apply；[GameRole 第 35 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/role/GameRole.kt:35)在 save 未抛异常时即判成功。
- **影响：** 异步磁盘写入失败时，H5 仍会收到成功，重启后资料可能丢失。
- **验证与建议：** 持久化 API 语义与调用链静态确认，未模拟磁盘失败。应让存储层提供持久化结果，再决定回调值，并避免主线程执行耗时磁盘写入。

## P3：展示与文档维护

### 14. GUI 只显示构建阶段，未实时展示 Gradle 构建进度

- **文档约定：** [需求第 404 行](E:/Yang/youxing_item/qishimeng_h5_sdk/安卓H5游戏包开发需求文档_requirements.md:404)要求展示 Gradle 构建进度。
- **实际实现：** [build_service.py 第 84 行](E:/Yang/youxing_item/qishimeng_h5_sdk/packager/build_service.py:84)仅上报“正在构建签名 APK”，随后 capture_output=True 等待子进程结束，中途不向 GUI 上报 Gradle 任务或依赖下载进度。
- **影响、验证与建议：** 首次下载依赖或长时间构建时缺少持续反馈。静态确认。可流式读取经过脱敏的任务进度，或明确文档只承诺阶段提示。

### 15. 渠道接线状态与历史实施计划存在陈旧描述

| 文档位置 | 当前文字与实现差异 | 建议 |
| --- | --- | --- |
| [分包设计第 46 行](E:/Yang/youxing_item/qishimeng_h5_sdk/安卓渠道分包设计文档_android_channel_packaging.md:46) | 仍称仓库没有 Android SDK 源码、读取器路径待定，实际已有完整 Android 工程和读取器 | 更新源码路径和当前交付状态 |
| [联调指南第 12 行](E:/Yang/youxing_item/qishimeng_h5_sdk/docs/安卓工程构建与联调指南_android-build-and-integration.md:12)、第 252 行及[导航第 13 行](E:/Yang/youxing_item/qishimeng_h5_sdk/文档导航_documentation.md:13) | 审查时将读取器列为尚未接入或仍需接入；实际 [MainActivity 第 477 行](E:/Yang/youxing_item/qishimeng_h5_sdk/android/app/src/main/java/com/qishimeng/game/MainActivity.kt:477)已写入 GameInfo，供请求 Header 和 Bridge 使用 | 已同步为“已接入，Android 真机测试已完成（用户确认）”；APK 验签结果单独记录 |
| [网络容错计划第 5 行](E:/Yang/youxing_item/qishimeng_h5_sdk/docs/superpowers/plans/H5页面网络容错_web-load-recovery.md:5)、第 11、14 行 | 仍写共三次、十秒超时及一/二秒退避；实际为共四次、五秒超时、立即重试，联调指南已同步 | 在旧计划标记已被后续调整替代，或补充实际结果 |
| [初始工程计划第 285 行](E:/Yang/youxing_item/qishimeng_h5_sdk/docs/superpowers/plans/2026-10-16-安卓游戏工程开发计划_android-game-implementation.md:285) | 写游戏加载后立即心跳；实际等待完整 interval，符合现行需求第 339 行 | 标注此步骤已被现行心跳规则替代 |

这些属于文档同步问题，不应据旧计划反向修改已经符合现行需求的实现。历史验证记录中的旧测试数量也不作为缺陷。

## 本轮未列为问题的事项

- 游戏常亮修改与联调指南一致：游戏首次成功显示后设置 FLAG_KEEP_SCREEN_ON；游戏关闭、退出及重新初始化清除。后台窗口不保持常亮是 Android 标志本身的作用范围，无需据此判断缺少 onPause 清理。
- 心跳首次间隔、隐藏悬浮球后继续轮询、旧心跳响应失效、功能实例保护与已存在运营实例不重载，主路径与现行文档相符。
- 通用本地存储与应用检测的正常主路径与文档相符；本报告第 5 项是它们之外的参数解码边界。
- Bridge on/off 是文档明确预留的事件；不按缺失功能报告。
- 一键登录的单次超时、自动重试次数和间隔已明确写为未解析、未实现；不重复报告。
- OAID 厂商实现尚未接入已由指南说明；不当作本次新发现。
- 分包文档的 PHP 部分是建议契约和示例，未承诺交付具体 PHP 文件；仓库没有 PHP 源码不单独作为缺陷。
- AGP 8.6.1 对 compileSdk 36 的支持警告属于工具链兼容提示，不直接等于代码与文档不一致。

## 验证结果与边界

| 验证 | 本轮结果 | 边界 |
| --- | --- | --- |
| Android testDebugUnitTest | 107 项通过，0 失败、0 错误、0 跳过；使用 --rerun 实际重跑 | JVM 测试，不覆盖真实 WebView、运营商和系统 UI |
| Android assembleDebug / assembleRelease | 构建通过 | 模板 Release 构建成功不代表 GUI 正式签名出包及分包验签完成 |
| Python packager 测试 | 20 项通过 | 含构建服务替身，不等于桌面 GUI 或真实签名构建验收 |
| Python tools 测试 | 2 项通过 | 合成 APK，不等于真实签名 APK 验签 |
| Node 示例测试 | 7 项通过 | 脚本环境，不是真实设备 Bridge |
| 定向诊断 | 旧登录 Token 回写、关闭认证的坏配置、缺 code 登录响应、NaN 布局异常、渠道换行及文件名碰撞 | 各条目已区分本地复现与静态推断 |
| git diff --check | 通过 | 仅检查差异中的空白问题 |

审查执行时，本机没有可用设备、AVD 或系统镜像，审查者未执行 connectedDebugAndroidTest；审查后用户已确认当前 Android 项目完成真机测试。以上问题不依赖“缺少真机测试”成立，涉及完整设备表现的结论仍需在修复后补设备回归。测试通过说明已有覆盖可运行，不能证明本报告列出的边界已满足契约。

## 建议处理顺序

1. 先修第 1 项会话隔离，再修第 2—5 项异常输入与回调契约。
2. 修复第 6—7、9—10、13 项导航、布局、渠道输入、成品命名及持久化边界。
3. 对第 8、11—12 项统一最终业务口径后调整实现或文档；保留已有明确视觉决策的依据。
4. 补齐第 14 项进度反馈并同步第 15 项陈旧说明。

本报告记录审查结论及后续进度。第 15 项的渠道文档状态已同步，其余未处理内容继续保留；本次没有修复业务代码。
