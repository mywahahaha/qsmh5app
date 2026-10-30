# 安卓渠道分包设计文档

## 1. 目的与适用范围

本文说明如何在 **Android APK 已完成 v2 或 v3 签名之后**，为每个发行渠道写入渠道码并生成独立 APK。渠道码会写入 APK Signing Block 的自定义条目，Android SDK 在应用安装、启动后自动读取，并向以下位置透传：

- SDK 初始化、运营心跳、一键登录兑换、本地 Token 登录请求的 `Packagecode` HTTP Header（PHP `$_SERVER` 常见映射为 `HTTP_PACKAGECODE`）；
- H5 JS Bridge `QSMH5APP.getGameInfo` 成功响应的 `data.packageCode`。

业务流程与字段口径以[安卓 H5 游戏包开发需求文档](安卓H5游戏包开发需求文档_requirements.md)为准；Bridge 方法签名以[JSBridge 方法清单](JSBridge方法清单_jsbridge.md)为准。

SDK 初始化接口固定硬编码为 `https://box.74meng.com/h5_app/init`。分包生成的 `packageCode` 不改变初始化主机；初始化响应的 `jumpDomain` 仅用于游戏内跳转外部浏览器，允许 `http`、`https` 并精确匹配主机名，不用于 JSBridge 来源校验，细节见需求文档。

例如渠道码 `1-25-6#10028` 会完整保存和返回；不得按 `#` 截断、拆分或 URL 编码后再写入。

本文是本项目完整 Android 游戏工程之外的渠道发行辅助流程，只适用于 Android APK。AAB 不能直接作为输入；应先由本项目的共用 Android Gradle 模板生成并签名 APK，再进行渠道分包。

> 当前仓库已提供 Python 分包脚本 `tools/channel_apk.py`；

## 2. 实现原理与边界

| 项目 | 约定 |
| --- | --- |
| 写入位置 | APK Signing Block 的自定义 ID-Value 条目 |
| 渠道条目 ID | `0x454D4348`（`EMCH`） |
| 值格式 | 完整渠道码的 UTF-8 字节 |
| 签名要求 | 输入 APK 必须已使用 v2 或 v3 签名 |
| 签名影响 | 只调整 Signing Block 和 ZIP Central Directory 偏移；不重新构建、不重新签名 |
| 覆盖规则 | 已存在同一渠道条目时替换，其他 Signing Block 条目保留 |
| 读取失败 | SDK 返回空字符串，不阻断 SDK 主流程 |

脚本会先写入同目录临时文件，再原子替换目标文件；母包不会被改写。输入 APK 和输出 APK 必须是不同路径。请不要并发向同一个输出路径发起多个分包任务。

渠道码限制如下：

- 必须是 UTF-8 字符串，编码后最多 4096 字节；
- 不能包含 NUL 字符（`\0`）；
- 可以为空；省略渠道码参数时会写入空值。

## 3. 组件与目录

| 路径 | 用途 |
| --- | --- |
| `tools/channel_apk.py` | Android APK 渠道分包脚本 |
| `tools/error.log` | 脚本预期失败时追加的错误日志；日志写入失败不改变脚本退出结果 |
| SDK 渠道码读取实现 | 需求要求从已安装 APK 读取渠道码；具体源码路径待 SDK 工程确定，当前仓库不包含 Android SDK 源码 |

脚本命令格式：

```text
channel_apk.py input_apk output_apk [package_code]
```

成功时退出码为 `0`，标准输出类似：

```text
渠道分包完成：<输出 APK 路径>
渠道码：<渠道码>
```

输入 APK 不存在、仅 v1 签名、Signing Block 格式非法、渠道码不合法等预期错误时退出码为 `1`，错误输出写入标准错误，并尽力追加到 `tools/error.log`。

## 4. 脚本使用环境要求

### 4.1 分包服务器或构建机

- Python 3.8 或更高版本，推荐 Python 3.9+；脚本只依赖 Python 标准库，无需安装第三方包。
- 运行账户必须具备：读取母包、创建输出目录、写入渠道包，以及向 `tools/error.log` 追加日志的权限。
- 母包和渠道包应放在非 Web 公开目录，下载应通过受鉴权的业务接口或受控对象存储完成。
- 推荐为每个任务创建独立输出目录，避免不同任务复用输出文件名。

### 4.2 可选的验签工具

分包本身不依赖 Android SDK Build Tools；但发布前必须验签。安装 Android SDK Build Tools 后可使用 `apksigner`：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs <APK路径>
```

请将命令中的 `36.0.0` 替换为机器已安装的 Build Tools 版本。

### 4.3 PHP 接口环境

若由 PHP 接口调度分包任务，还需要：

- `proc_open`、`proc_close` 未被 `disable_functions` 禁用；
- PHP-FPM/Apache 运行账户能执行 Python，并拥有上述母包、输出目录和日志目录权限；
- PHP 配置或环境变量中提供 Python 解释器与脚本的**绝对路径**；
- 接口必须有鉴权，并限制可选母包、输出根目录和渠道码格式。绝不能将请求中的命令、任意绝对路径或文件名直接交给系统执行。

## 5. 母包准备

1. 依照正式发布流程构建 APK。
2. 对 APK 完成 v2 或 v3 签名，并先执行一次 `apksigner verify`。
3. 将该已签名 APK 作为只读母包，为每个渠道生成不同的输出 APK。
4. 不要对分包后的 APK 再执行 `zipalign`、重新压缩或重新签名；这类操作可能移除或改变渠道块。

若输入包只有 v1 签名，没有 APK Signing Block，脚本会失败并报“输入 APK 必须使用 v2/v3 签名”。

## 6. 终端执行方法

### 6.1 Windows PowerShell

在仓库根目录执行。若系统已配置 `python` 命令，使用：

```powershell
python .\tools\channel_apk.py `
  .\android\build\signed-apk\release\android-release.apk `
  ".\android\build\channel-apk\fengwujiutian_1-68-5#168.apk" `
  "1-68-5#168"
```

若 Windows 使用 Python Launcher，则将第一段替换为本机实际版本，例如：

```powershell
py -3.8 .\tools\channel_apk.py `
  .\android\build\signed-apk\release\android-release.apk `
  ".\android\build\channel-apk\fengwujiutian_1-68-5#168.apk" `
  "1-68-5#168"
```

渠道码和包含 `#` 的输出文件名应始终使用引号，避免在不同终端中被误解释。输出目录不存在时脚本会自动创建。

### 6.2 macOS/Linux

```bash
python3 tools/channel_apk.py \
  '/data/apk/source/android-release.apk' \
  '/data/apk/output/fengwujiutian_1-68-5#168.apk' \
  '1-68-5#168'
```

### 6.3 写入空渠道码

省略第三个参数，或明确传入空字符串：

```bash
python3 tools/channel_apk.py source.apk output.apk
```

## 7. PHP 接口使用方法

### 7.1 接口约定

建议将接口定义为仅接受 JSON 的 `POST /api/channel-package`。客户端只传母包逻辑标识，不传服务器绝对路径；服务端通过白名单映射到固定母包。

请求示例：

```json
{
  "base_apk": "fengwujiutian_1.0.6",
  "package_code": "1-68-5#168",
  "job_id": "release-20261014-001"
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `base_apk` | 是 | 服务端登记的母包 ID，不是文件路径 |
| `package_code` | 是 | 要写入 APK 的完整渠道码 |
| `job_id` | 是 | 业务方生成的任务 ID，仅允许字母、数字、`_`、`-`；用于隔离输出目录 |

### 7.2 调用 PHP 接口

```bash
curl -X POST 'https://pack.example.com/api/channel-package' \
  -H 'Authorization: Bearer <业务令牌>' \
  -H 'Content-Type: application/json' \
  --data '{"base_apk":"fengwujiutian_1.0.6","package_code":"1-68-5#168","job_id":"release-20261014-001"}'
```

成功响应示例：

```json
{
  "success": true,
  "status": "succeeded",
  "jobId": "release-20261014-001",
  "packageCode": "1-68-5#168",
  "apkPath": "/data/apk/output/release-20261014-001/fengwujiutian_1.0.6_1-68-5_168.apk"
}
```

失败时示例接口返回 HTTP `422`；配置、权限或进程启动失败返回 HTTP `500`。脚本细节应仅写入服务端日志，不应直接返回给调用方。

### 7.3 异步任务和回调建议

大 APK 或批量分包不应由 PHP-FPM 请求长时间同步执行。推荐流程为：

```text
调用方请求 → API 校验并创建 job_id → 队列 Worker 调用 channel_apk.py
        → 记录 succeeded/failed 状态 → 调用方查询或接收业务回调
```

Worker 仍应沿用上述白名单、路径隔离、参数校验与退出码判断；同一个 `job_id` 应保证幂等，同一输出 APK 同时只能有一个 Worker 写入。

## 8. 分包后验签与安装验收

### 8.1 验证签名

分别对母包与渠道包执行：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs .\android-release.apk
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs .\fengwujiutian_1-68-5#168.apk
```

两个 APK 都必须验证通过，且输出的签名者证书 SHA-256 指纹必须一致。APK 文件整体 SHA-256 会因渠道条目不同而变化，这是正常现象，不能据此判断签名不一致。

### 8.2 SDK 回读验收

1. 卸载旧安装包，安装本次渠道 APK。
2. 启动游戏并完成 SDK 正常初始化。
3. 通过实际 H5 Bridge 调用 `QSMH5APP.getGameInfo`，检查 `data.packageCode` 是否为 `1-68-5#168`。
4. 在服务端日志或抓包中检查 SDK 请求 HTTP Header `Packagecode` 是否为同一完整值（HTTP Header 名称大小写不敏感；PHP `$_SERVER` 常见读取键为 `HTTP_PACKAGECODE`）。

若未写入渠道块、渠道块损坏或 SDK 读取失败，`Packagecode` Header 与 `QSMH5APP.getGameInfo` 的 `data.packageCode` 都会是空字符串，SDK 其他流程仍可继续。这种情况应回查分包输入包、脚本退出码和验签结果。

## 9. 常见问题

### 9.1 终端执行后没有生成文件

- 确认当前工作目录、`tools/channel_apk.py` 路径和母包路径正确；
- Windows 的 `python` 可能指向 Store 安装别名，可改用 `py -3.8` 或机器实际 Python 路径；
- 检查输出目录写权限，并查看 `tools/error.log`；
- 包含 `#`、空格等字符的路径和渠道码必须使用引号；
- 检查输入 APK 是否已经 v2/v3 签名。

### 9.2 分包后 APK 签名是否被改了

脚本不会重新签名。应使用 `apksigner verify --print-certs` 比较分包前后的签名者证书 SHA-256，而不是比较 APK 文件哈希。

### 9.3 `QSMH5APP.getGameInfo` 返回空 `packageCode`

先确认安装的是新生成的渠道 APK，而非旧母包；再确认分包脚本成功退出、验签通过，并确认 H5 是通过 SDK 注入的 `QSMH5APP.getGameInfo` 调用。SDK 未读取到渠道块时按需求返回空字符串。

### 9.4 PHP 接口能否直接接收 APK 绝对路径

不能。接口应只接收登记的母包 ID，由服务端白名单映射到固定路径；否则会造成任意文件读取、覆盖或命令执行风险。
