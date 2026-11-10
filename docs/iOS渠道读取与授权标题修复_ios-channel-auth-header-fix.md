# iOS 渠道读取与授权标题修复

日期：2026-11-03。版本：1.0.1，构建 8。

## 渠道问题与修复

用户样本 `~/Downloads/qmhy_1-218.ipa` 的版本为 1.0.1（构建 6），ZIP 内确实包含 `Payload/QSMH5App.app/packCode/1-218/`，是空渠道目录，格式符合约定。

原运行时入口先枚举 `Bundle.main.bundleURL` 的父目录，再找 `.app`。真机沙盒不保证允许列出安装容器，父目录枚举失败就返回空字符串；离线解包或模拟器中父目录可读，因此原有目录测试未覆盖此问题。

构建 8 改为直接从当前 `Bundle.main.bundleURL/packCode/` 读取直接子目录。Bundle 已确定自身应用位置，不需要再列出父目录，也不依赖应用目录固定名称。不读取同级应用的渠道，不使用 Info.plist 旧值或渠道缓存。

- `1-218/` 对应 `getGameInfo.data.packageCode = "1-218"`。
- 初始化、登录、心跳等原生请求通过同一个 `GameIdentity` 设置 `Packagecode: 1-218`。
- H5 自行发出的请求仍需由 H5 通用封装读取 `getGameInfo` 设置 Header；原生不拦截改写网页的所有请求。
- 任一目录缺失、不可读或没有有效渠道时返回空，不阻塞启动，也不创建任何目录。
- 保留 `read(payloadDirectory:)` 作为离线解包读取入口，继续枚举全部直接下级 `.app`，按应用名和渠道名排序；不再用于安装后的启动入口。

新增“只允许枚举自身 packCode、拒绝列出父目录”的替身回归，并覆盖同级应用有其他渠道时不误读。完整链路测试改用 `RuntimeConfiguration.load(bundle:)`，验证隐私同意前后 Bridge 与实际 URLRequest Header 都为 `1-218`。对用户 IPA 解包后，用修复后的 Swift 读取器实际得到 `1-218`。

## 一键登录标题

- 标题高度由 43.7pt 增至 53.7pt，增加 10pt。
- Logo 等比显示框由 90×30pt 放大为 102×34pt，保持左距 10pt，按整个标题区域垂直居中。
- 关闭按钮保持 44×44pt 点击区域，图形与按钮均按整个标题区域垂直居中，去掉原来的向上偏移。
- 内容布局使用同一标题高度。紧凑横屏仅将非触控间距压至 2pt，保持号码预留高度、44pt 按钮点击区域、协议字体与完整条款；无法容纳仍回退 H5。

横屏 50%×70%、横屏 60%×75%、竖屏 90%×65% 的原生布局预览已检查，无重叠。截图位于 `ios/artifacts/授权标题预览_build8/`，号码为虚构替身，不能当作真实 SIM 取号验收。

## 验证与交付

- 核心回归 70 项通过：`ios/artifacts/test-run.kx88jj/Tests.xcresult`。
- 样本 IPA 解包与读取记录：`ios/artifacts/渠道样本验证_build8/`。
- 一键登录真实 SDK 配置专项 3 项通过：`ios/artifacts/渠道标题SDK专项_build8.xcresult`。
- 母包与 `1-218` 渠道包均完成 Release 归档、Ad Hoc 导出、严格验签与生产配置校验；母包没有任何 `packCode` ZIP 条目，渠道包有 `packCode/1-218/`，用修复后的读取器读取渠道包归档实际得到 `1-218`。

| 产物 | 路径 | SHA-256 |
| --- | --- | --- |
| 空渠道母包 | `ios/artifacts/交付_20261103_渠道读取与标题修复_build8_母包/29_com.un74meng.yjhcmc_1.0.1_8_AdHoc.ipa` | `89c6b2bb440ded2a2613e9037b61811b0a695d12b79c8316eca0b0ce0511a95b` |
| 渠道 1-218 | `ios/artifacts/交付_20261103_渠道读取与标题修复_build8_1-218/29_com.un74meng.yjhcmc_1.0.1_8_AdHoc.ipa` | `673b9a587bb5d2497689fa67a6c3a119065f7f83aab5c1783a5b08085abd9c01` |

两包均沿用游戏 29、Bundle ID `com.un74meng.yjhcmc`、横屏、用户图标和闪图，构建号为 8；原用户配置及原 IPA 未覆盖。签名包检查结果另存 `ios/artifacts/渠道标题修复_build8_最终验证.json`，产物目录包含 dSYM 和构建日志。Mac 打包器继续选当前仓库的 `ios/` 工程即可带入本轮运行时代码修复。

需使用构建 8 的新母包重新分包；旧 IPA 仅写入目录不会更新读取代码。真机安装后检查初始化请求的 `Packagecode` 和 `getGameInfo.data.packageCode` 均为 `1-218`，退出登录再复验新的标题布局与取号交互。本轮不连接 iPhone，真机安装与实际取号由用户执行。
