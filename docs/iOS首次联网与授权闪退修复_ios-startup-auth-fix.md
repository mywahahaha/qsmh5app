# iOS首次联网与授权闪退修复

日期：2026-11-03。对应真机构建 5 的反馈；修复交付构建号为 6，版本保持 `1.0.1`。

## 反馈与定位

用户确认安装、桌面图标正常，首次启动等待系统联网授权时出现初始化失败白屏；允许联网后可打开协议，调用 `agreePrivacyPolicy` 后闪退，后续启动也闪退。

### 初始化联网

此前网络错误直接进入初始化失败状态，同时闪图按普通最长 5 秒时序结束。首次联网授权尚未完成就可能展示失败白屏；应用没有等待网络恢复的初始化重试流程。

### 隐私同意后的授权

当前线上初始化开启隐私协议及阿里云一键登录。因此首次同意隐私和已同意后重启，都会进入阿里云授权流程；Bridge 方法名称本身没有变化。

本机官方 SDK `TXCustomModel.h` 明确授权页默认方向为竖屏，旧代码没有覆盖。游戏包仅支持横屏，宿主与授权页面无共同方向，会触发 UIKit 的方向检查异常。

用户随后提供 `QSMH5App-2026-11-03-141021.ips`，确认是 iOS 18.2、版本 1.0.1 / 构建 5、主线程 `EXC_CRASH / SIGABRT`。崩溃 App UUID 与用户构建 5 归档 dSYM 完全匹配：`7A35424D-116B-3AB7-814B-979294F2CC05`。异常栈包含：

```text
objc_exception_throw
-[UIViewController __supportedInterfaceOrientations]
-[_UIFullscreenPresentationController _prepareForMixedOrientationTransitionIfNecessaryInWindow:fromViewController:toViewController:]
-[UIViewController presentViewController:animated:completion:]
__82-[UIViewController(PNSAnimation) presentController:model:isFullScreen:completion:]_block_invoke
    PNSAnimationManager.m:52
-[UIViewController(PNSAnimation) presentController:model:isFullScreen:completion:]
__86-[PNSBizService presentLoginPageWithRequest:vendorConfig:loginCache:isDebug:complete:]_block_invoke
    PNSBizService.m:530
```

后 3 帧由匹配 dSYM 使用 `atos` 还原，确认异常发生在阿里云授权页展示时的方向检查，与上述配置冲突一致。日志未包含异常原因文字，不能从中逐字引用异常名称。去除设备标识的符号化结果保存在 `ios/artifacts/崩溃栈符号化_build5.txt`。

## 修复内容

- HTTP 会话启用 `waitsForConnectivity`；系统通知等待网络时，保留闪图并显示等待提示及系统设置入口。
- 初始化对断网、网络中断、超时、DNS 等可恢复网络错误自动重试，间隔 2、4、6、8、10 秒，之后保持 10 秒；允许联网后由等待中的请求或下次重试继续。
- 等待初始化联网期间不计入闪图普通 5 秒上限；初始化成功后恢复正常 1.2–5 秒时序。业务、TLS、配置和存储错误仍按错误流程展示。
- 初始化取消、重新开始时取消旧请求及重试等待，旧结果不能覆盖当前流程。未同意隐私前仍不发送设备码或账号 Token。
- 阿里云 `TXCustomModel.supportedInterfaceOrientations` 从实际 App 的 Info.plist 读取，横屏包使用横屏、竖屏包使用竖屏；协议配置改用安全解析，配置无效时回退 H5。
- SDK 弹窗前撤去闪图，保留原有登录回退、隐私与 Bridge 业务流程。

参考：[Apple 网络等待](https://developer.apple.com/documentation/foundation/urlsessionconfiguration/waitsforconnectivity)、[Apple 方向异常](https://developer.apple.com/documentation/uikit/uiapplication/invalidinterfaceorientationexception)、[阿里云 iOS 接入](https://help.aliyun.com/zh/pnvs/developer-reference/the-ios-client-access)。

## 验证记录

| 项目 | 结果与证据 |
| --- | --- |
| 核心回归 | 61 项通过；含网络错误筛选、重试恢复、隐私边界、业务失败、旧初始化取消与方向映射 |
| 模拟器 UI | 14 项通过；新增首次联网等待超过 5 秒保留闪图、恢复后协议/登录/游戏及已同意重启场景 |
| 测试记录 | `ios/artifacts/test-run.P6G4m1/Tests.xcresult` 和同目录 `xcodebuild.log`、`fixture-requests.json` |
| 等待画面检查 | 已导出并查看 `ios/artifacts/联网等待截图_build6/15F51991-0AF9-4BC7-B159-B1346CA8A02F.png`，闪图仍在，无初始化失败白屏 |
| 真实 SDK 方向专项 | 2 项通过；直接创建真实 `TXCustomModel` 验证默认竖屏、修复后横屏及竖屏配置；`ios/artifacts/SDK方向测试_单份SDK.xcresult`、`device-feedback-sdk-single-link.log` |
| 签名出包 | 构建 6 Release 归档、Ad Hoc 导出、最终 IPA 严格验签和配置检查通过；profile 登记 7 台设备 |
| 空渠道 | 最终 ZIP 中 `packCode` 条目为 0；游戏地址覆盖为空，实际为双向横屏 |

网络替身通过主动断开初始化连接模拟联网暂不可用；不会将模拟器结果等同于真机系统联网授权或运营商取号验收。SDK 专项以 Debug-Integration 构建测试宿主，只给测试 target 提供 SDK 头文件搜索路径和编译条件，静态 SDK 仅由宿主链接，避免重复类干扰。

## 交付配置

- `gameId=29`，Bundle ID `com.un74meng.yjhcmc`，版本 `1.0.1`，构建号 `6`，横屏。
- 沿用用户保存的构建 5 图标和横版闪图；`channel_code`、`game_url` 都为空。游戏地址由初始化返回；空渠道不产生 `packCode` 目录。
- 用户原配置和构建 5 产物保留；本次配置副本位于 `ios/artifacts/修复构建6_ios-build6.json`。
- 签名包：`ios/artifacts/交付_20261103_联网等待与授权方向修复_AdHoc/29_com.un74meng.yjhcmc_1.0.1_6_AdHoc.ipa`。Ad Hoc 安装限描述文件登记设备；归档和 dSYM 保留用于崩溃符号化。
- IPA SHA-256：`7ae3a5c9c4423a9516a70d222d6c6865c8ba09b82c7f7c36693b4b1c5ec987a7`。

## 真机复验与日志

1. 先覆盖安装构建 6，保留已经同意隐私的状态，验证启动和授权不再闪退；取消或取号失败应能回到 H5 登录。
2. 需要检查首次安装流程时，再按测试需要重新安装：等待系统联网提示期间保留闪图，不显示初始化失败白屏；允许联网后自动打开协议，点击同意后验证登录和游戏。
3. 验证拒绝联网时仍显示等待提示，前往系统设置允许网络后可以继续。
4. 已收到并分析旧构建 5 日志；如修复包仍闪退，提供最新构建 6 的 `.ips`，按新栈继续排查。

日志入口：iPhone「设置 → 隐私与安全性 → 分析与改进 → 分析数据」，找到最近的 `QSMH5App-日期时间.ips`，点开并通过分享按钮隔空投送到 Mac。若刚发生崩溃列表尚未显示，可稍后重新进入。日志由用户自行分享，不需要连接设备。
