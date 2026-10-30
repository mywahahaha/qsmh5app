# 心跳与运营弹窗完善实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目标：** 接通登录大厅后的心跳请求，并按服务端返回同步悬浮球和运营弹窗。

**架构：** 使用现有 `ApiClient` 和可注入调度器实现真实心跳；`MainActivity` 在悬浮球实际显示后启动、在大厅关闭/退出/销毁时停止。窗口注册表记录运营弹窗类型，心跳只同步该类型；动态运营弹窗根据心跳下发初始 URL 绑定可信主机，通过原生消息入口校验主 frame 和来源并安装 JSBridge。

**技术栈：** Kotlin、Android WebView、`HttpURLConnection`、JUnit。

## 全局约束

- 首次请求延迟使用 `popupInterval.interval` 秒，不固定 1 秒。
- `code != 0` 不应用任何业务数据。
- 已存在的运营弹窗按 `instance` 完全不刷新、不换 URL、不重新置顶。
- 初始化 `h5Pages` 功能页面不能被运营弹窗清理。
- 本轮只运行模拟/单元测试，不进行真机打包。

## Review Focus

- 心跳配置缺失、间隔为 0 或负数时不能创建失控的调度任务。
- 心跳 HTTP 错误或非法 JSON 时必须继续下一轮。
- `popup_pages` 为空时只关闭旧运营弹窗，不关闭功能页面。
- 动态弹窗同 instance 已存在时不能触发二次打开。
- `code != 0` 时悬浮球和弹窗都不能改变。

### Task 1：心跳响应模型、解析和真实请求

**文件：**
- 修改：`android/app/src/main/java/com/qishimeng/game/network/ApiClient.kt`
- 修改：`android/app/src/main/java/com/qishimeng/game/network/PopupHeartbeatService.kt`
- 新增/修改测试：`android/app/src/test/java/com/qishimeng/game/network/*`

实现 `popupInterval.url` 的 POST 请求、统一请求头和 `{code,msg,data}` 解析；首次调度与后续调度均使用配置间隔秒数，异常继续调度。

### Task 2：运营弹窗类型隔离与动态 Bridge

**文件：**
- 修改：`android/app/src/main/java/com/qishimeng/game/web/PopupWindowCoordinator.kt`
- 修改：`android/app/src/main/java/com/qishimeng/game/web/WebInstance.kt`
- 修改：`android/app/src/main/java/com/qishimeng/game/web/WebContainer.kt`
- 修改：`android/app/src/test/java/com/qishimeng/game/web/*`

窗口注册时记录是否为运营弹窗；协调器只关闭不在新清单中的运营弹窗。新运营弹窗的 Bridge 信任绑定心跳下发初始 URL 主机，不要求预先登记在初始化配置中；使用原生 WebMessageListener 校验消息来源及主 frame。既有 instance 不调用打开逻辑。

### Task 3：MainActivity 主流程接线

**文件：**
- 修改：`android/app/src/main/java/com/qishimeng/game/MainActivity.kt`

在悬浮球实际出现后创建并启动服务，响应在主线程应用；退出登录、关闭大厅、销毁 Activity 停止服务。非零 code 忽略；零 code 更新悬浮球并同步运营弹窗。

### Task 4：模拟测试与回归验证

运行心跳、窗口、配置、悬浮球及全量 debug 单元测试，确认没有编译或回归失败。

## 实施记录与边界

- 现有 Android 目录属于用户未跟踪工作，留在当前 `codex/aliyun-dialog-ui` 分支原位修改，未迁移工作树、提交或覆盖其他变更。
- 调度、会话状态、真实 HTTP 请求、响应解析、运营窗口分类与 Bridge 策略已实现。
- 审查发现的“隐藏球后新会话无法重启”和“动态原生 Bridge 子 frame 来源未校验”已补回归并修正。
- 默认请求沿用项目 POST/JSON `{}` 和六项请求头；若后端另需额外字段，须按实际契约调整。
- 不支持安全消息入口的旧 WebView 不授予运营页原生能力；设备端实际注入、跨域隔离及线上接口仍由用户出包验收。
- 心跳启动后临时隐藏球不停止；停止使返回结果失效，但不承诺中断已发出的 HTTP 连接。下一请求按上次完成后间隔计时。
- 最终验证：`:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` 成功；JUnit XML 汇总 107 项测试，失败/错误/跳过均为 0。设备测试仅编译，未打包、安装或执行。现有 AGP/compileSdk 兼容提示及弃用警告保留。
