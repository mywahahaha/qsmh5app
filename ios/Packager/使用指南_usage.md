# iOS 游戏打包器使用指南

版本：首版，2026-11-03。工具在 macOS 上运行，要求完整 Xcode、iOS SDK 和 Python 3.11+；当前 Intel Mac、Xcode 16.4 已验证。无需手改 Xcode 工程。首次使用先阅读[实施记录](../../docs/iOS图片与打包器实施记录_ios-assets-packager-development.md)。

2026-11-04 更新：当前工程的最低运行系统已降为 iOS 14.0，具体兼容改动、构建 9 签名包与真机验收边界见[实施记录](../../docs/iOS14兼容实施记录_ios14-compatibility.md)。打包器从所选 `ios/` 工程读取目标版本；重新出包需选择 2026-11-04 更新的描述文件，之前生成的 IPA 不会自动改变最低系统版本或内嵌设备清单。

## 一、打开图形工具

本机已生成 `ios/artifacts/iOS游戏打包器_正式.app`，可双击打开。它使用本机临时签名，未进行 Developer ID 公证；跨 Mac 分发需要另行配置环境与工具签名，当前交付面向本机使用。

需要从源码重新生成时，在仓库根目录运行：

```bash
ios/Packager/构建打包器_build_packager.sh /本机输出目录/iOS游戏打包器.app
```

输出路径已存在时拒绝覆盖，请选择新路径。工具内置后端脚本；修改后端后必须重新生成工具。该 `.app` 不包含整个游戏工程、Xcode、Python 或阿里云 SDK，迁移后在“环境与签名”页重新选择对应路径。

## 二、配置游戏与图片

可点击“加载配置”，选择 `ios/Packager/游戏29示例_game29.json`。样例相对路径以所选 `ios/` 工程的父目录为基准。点击“保存配置”会同时保存游戏字段，以及工程目录、Python、描述文件、签名身份、P12/PFX 路径和输出父目录；再次加载时自动填入。证书密码不保存，加载配置时也会清空当前密码输入。旧版配置没有打包器设置时仍可加载，环境与签名字段保持当前值。配置包含本机绝对路径，换 Mac 后需重新选择对应文件和目录。

| 字段 | 用法 |
| --- | --- |
| 游戏 ID / 桌面名称 | 例如 `29` / `全民红月` |
| Bundle ID | 例如 `com.un74meng.yjhcmc`，必须符合所选描述文件 |
| 版本 / 构建号 | 例如 `1.0.1` / `4`；首版构建号使用最多 9 位正整数 |
| 方向 | 横屏支持左右横屏；竖屏仅正向竖屏 |
| 图标 | 正方形 PNG，推荐 1024×1024、不透明、无预制圆角 |
| 兼容旧图标 | 显式勾选后可将正方形旧图缩放为 1024 并铺黑底，摘要记录转换；不能恢复低分辨率细节 |
| 闪图目录 | 可留空；读取直接下级文件，文件名包含实际 `宽x高`，例如 `横版-1280x768.png`；只接受 PNG，实际像素必须匹配命名，方图报错 |
| 游戏地址覆盖 | 留空使用初始化接口的游戏地址；填入时仅覆盖初始游戏 URL，仍需与接口配置同源 |
| 渠道码 | 可选，如 `1-218`；留空为母包，完全不打入 `packCode/` 目录，填写后在签名前生成 `packCode/1-218/` |

图标和闪图会完整解码检查，单文件上限 16MB，尺寸最多 10000×10000 且不超过 6000 万像素。闪图同尺寸重复时按文件名排序保留首个，并在日志提示；子目录不递归读取。可使用现有 `refs/打包资料/证书/闪图/`（10 个 PNG、9 种唯一尺寸），或整理后的单层目录。

游戏 29 样例保留此前指定的完整游戏地址。制作新游戏时应明确填写或清空地址，不只修改 URL 中的 `gid`。认证方案由服务端按 iOS Bundle ID 配置。

## 三、选择环境与签名

1. 工程目录选仓库 `ios/`，不是 `.xcodeproj` 文件，也不是仓库根目录。
2. Python 选 3.11+ 可执行文件。本机生成工具时已写入 Python 3.12 路径；终端的裸 `python3` 可能是旧版，请优先使用下方 shell 入口。
3. 选择 `.mobileprovision`，签名身份可自动匹配或从钥匙串明确选择。
4. 已有有效身份无需再次导入。只有 `.cer` 不够，缺私钥时选择匹配的 `.p12/.pfx`，在安全输入框填写密码并点“导入到登录钥匙串”。密码不传给后端、不放进命令行/JSON/日志；导入后清空密码。系统可能要求授权。
5. 选择输出父目录，点击“预检”。日志显示描述文件 Team、授权范围、到期时间、分发类型及设备数量。

当前用户提供的 `h5App.mobileprovision` 为 Ad Hoc，允许 `com.un74meng.*`，登记 7 台设备，到期 UTC 2027-11-02 08:15:15。不能用于任意 Bundle ID 或任意 iPhone；更换签名材料后以预检结果为准。

SDK 本机配置 `Config/Aliyun.local.xcconfig` 及 `Vendor/Aliyun/SDK/` 会参与构建。普通 `LocalOverrides.xcconfig` 不复制到临时工程，避免旧团队、包名覆盖本次配置。

## 四、出包与取消

“开始签名出包”依次执行资源校验、生成隔离工程、归档、导出、最终 IPA 校验和发布。界面展示阶段与详细日志路径；Xcode 归档耗时随设备和工程变化，未给出虚假的整体百分比。

最终 IPA 验证通过后才发布成功目录。目录名称包含游戏 ID、Bundle ID、版本、构建号、时间及任务后缀。内含 IPA、xcarchive/dSYM、构建日志、`出包摘要.json`、`SHA256.txt` 和安装说明。描述文件按任务临时安装，结束后恢复原状态。

取消会停止后端及 Xcode 子进程、恢复临时描述文件并清理工作区；界面显示退出码 130。失败/取消仅保留独立的“失败日志”目录，不发布成功包、不覆盖已有产物。退出工具时，如有任务会先取消清理再退出；强制结束进程或断电不保证执行清理。

需要延续原渠道脚本时，母包保持渠道码为空。更改已签名 IPA 的图标、配置或资源必须重新签名，不能直接压缩交付。

## 五、命令行用法

在仓库根目录运行，入口自动选择 Python 3.11+ 并定位 Xcode：

```bash
# 预检
 ios/Packager/运行打包器_package.sh check \
  --source ios --config ios/Packager/游戏29示例_game29.json \
  --profile 游戏出包/iOS/证书/h5App.mobileprovision

# 签名构建；输出目录必须不存在
 ios/Packager/运行打包器_package.sh build \
  --source ios --config ios/Packager/游戏29示例_game29.json \
  --profile 游戏出包/iOS/证书/h5App.mobileprovision \
  --output ios/artifacts/本次签名出包

# 仅验证多游戏资源和归档，未签名 IPA 不能安装
 ios/Packager/运行打包器_package.sh build --unsigned \
  --source ios --config /本机路径/游戏配置.json \
  --output ios/artifacts/本次未签名构建
```

可用 `--identity` 指定钥匙串 SHA-1 身份。`identities` 列出有效签名身份；`inspect-profile --profile 路径 --bundle 包名` 检查材料。后端不接受私钥密码，不执行账号登录或商店上传。多个构建任务以本机锁串行执行。

兼容入口：

```bash
/usr/local/opt/python@3.12/bin/python3.12 ios/Scripts/export_signed.py \
  --profile 游戏出包/iOS/证书/h5App.mobileprovision \
  --config ios/Packager/游戏29示例_game29.json \
  --output ios/artifacts/另一份签名包

ios/Scripts/archive_unsigned.sh ios/artifacts/另一份未签名包 /本机路径/游戏配置.json
```

`verify_archive.py 归档路径 --config 配置路径` 按本次游戏配置验证归档；最终导出 IPA 的严格校验由打包后端独立执行。

## 六、验证范围

当前已验证：模拟器图片及原流程、后端单测、两套配置交叉构建、真实 Ad Hoc 最终 IPA 验签、图形界面预检/切页/图片预览/启动/取消及再次出包。

未执行：本轮新增私钥导入、真机安装、真实 SIM 取号与第二个真实线上游戏验收。已有签名身份用于本轮出包，不需要重新提供密码。
