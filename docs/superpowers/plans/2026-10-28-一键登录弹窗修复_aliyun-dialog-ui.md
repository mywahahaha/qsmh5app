# 一键登录弹窗修复计划

> 执行方式：本会话直接实施，使用 executing-plans、test-driven-development 和 verification-before-completion；完成后进行独立代码审查。

**目标：** 按用户提供的参考图修复手机上的阿里云授权弹窗。

**需求依据：** 2026-10-28 本会话的两张截图、box_logo.png 及逐项样式要求。

**实现：** 保留阿里云 SDK 提供的手机号、登录、运营商说明和协议控件；明确设置弹窗背景、尺寸、遮罩及控件布局，使用 SDK 自定义布局扩展品牌头部与关闭按钮。

## 全局约束

- 宽高分别读取 dialog_width_percent、dialog_height_percent，按当前授权页方向和可用窗口计算。
- 黑色遮罩透明度 45%，灰色底色 #eeeeee，圆角 14dp。
- 红色 #c52d28 的头部约 30dp，底边向上形成浅弧；白色 Logo 等比缩放；关闭图标右距约 10dp、在红色部分垂直居中。
- 忽略 title；登录按钮使用 login_text、橙色背景、白字；协议详情页标题栏 #c52d28。
- 不改变用户当前的隐私勾选状态要求和原有登录兑换逻辑。
- 中文沟通和文档；保留现有未提交文件，不把整个未跟踪的 Android 工程提交。

## 审查重点

- 横屏小高度时各控件、协议换行不能重叠。
- 横竖方向与宽高百分比保持独立，不能强行放大弹窗。
- SDK 单例再次使用时不残留自定义 View、监听器或重复回调。
- 关闭、返回键、其他登录方式都能退出授权页并回退。
- SDK 实际窗口渲染与配置值一致；模拟器的离线渲染不视为手机蜂窝认证验收。

## 任务一：修复授权弹窗并验证

- [x] 添加设备回归：真实 AuthUIConfig 的背景、遮罩、标题、文案和控件位置；运行并确认旧实现失败。
- [x] 引入 Logo、弧形品牌头部、圆角背景及登录按钮/协议勾选样式；修复 SDK 配置和关闭清理。
- [x] 使用 SDK 真实授权 Activity 的离线测试数据验证控件、关闭和协议导航，保存界面截图。
- [x] 运行 Android 单元测试、设备测试及 Release 构建；核对输出和界面截图。
- [x] 独立审查并处理重要问题，更新构建联调文档。

## 执行记录

- 初始专项：3 项中 2 项因默认标题未隐藏、控件位置未配置而失败；修复后 3 项通过。
- 实际 SDK 渲染发现底部协议被压缩，补充按文本 Layout 高度和末行结尾检查的断言；扣除 50dp 内容区偏移并将协议从底部定位后恢复完整显示。
- 独立审查发现 180dp 小高度及自行取消模式返回键两项重要问题，已添加专项并复现；紧凑布局、UI 点击监听器和终态清理已修复。
- 采用现有未提交工作区，并新建 `codex/aliyun-dialog-ui` 分支；不复制缺少未跟踪 Android 工程的 Git 工作树，不批量提交用户已有文件。
- 对 UI 的 px 描述按 Android dp 实现；原始 Logo 直接使用，未重新生成。
- 最终执行 `testDebugUnitTest assembleRelease connectedDebugAndroidTest`：68 项单元测试通过；50 项设备测试中 46 项通过、4 项按配置跳过；Release 编译及关键 Lint 检查成功。日志：`android/build/onekey-ui-verification/final-suite.log`。
- 本次未使用签名密码重新生成正式母包或渠道包；现有正式 APK 保留。出包工具下次构建会使用修复后的模板。

## 验证命令

- Android SDK 路径通过 ANDROID_HOME / ANDROID_SDK_ROOT 注入。
- `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.qishimeng.game.auth.OnekeyAuthUiTest`
- `./gradlew.bat testDebugUnitTest assembleRelease connectedDebugAndroidTest`

预期：新增用例先因旧样式失败，修复后通过；全量测试通过或明确报告环境跳过与失败；Release APK 编译成功。
