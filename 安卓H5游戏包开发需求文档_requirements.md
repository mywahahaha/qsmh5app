# 安卓 H5 游戏包开发需求文档

## 文档信息

- 项目：qishimeng_h5_sdk
- 文档版本：v0.17
- 更新日期：2026-10-29
- 状态：业务口径已定稿；服务端接口契约与工程实现细节待联调补充

## 1. 项目概述

本项目从零开发可复用的 Android 原生游戏工程（壳应用）及其 Windows 可视化出包工具，面向多个 H5 游戏生成独立配置和签名的 Android 母包。原生 APP 负责初始化、网络、隐私、登录、更新、WebView 容器、悬浮球、JSBridge、设备码及角色信息管理；H5 承载游戏与业务页面。渠道分包脚本、Vue2 示例和阿里云号码认证 SDK 是辅助参考资料，不是待交付的 Android 主工程。

主要交付物：可构建的 Android Studio/Gradle 工程、可复用多游戏模板、Windows GUI 出包工具、已签名母包，以及独立渠道分包工具/流程。

## 2. 名词解释

- `accountToken`：登录令牌。手机号一键登录成功或 H5 页面登录成功后，由 JS 通知游戏登录成功时返回，供 H5 游戏页面获取，并在应用本地缓存。H5 可通过退出接口通知游戏清除该令牌。
- `auth_token`：阿里云一键登录授权凭证，用于服务端验证；验证成功后返回 `accountToken`，验证失败则唤起 H5 登录页。
- `packageCode`：渠道码，从安装包读取，由分包脚本写入安装包；请求 Header 字段名为 `Packagecode`。
- `gameVersion`：游戏版本号，出包时指定并由 APP 作为常量保存；请求 Header 字段名为 `Gameversion`。
- `deviceCode`：用户同意隐私协议后生成或取得的设备标识；用户未同意时为空。APP 请求 Header 使用 `Devicecode`。
- 游戏 ID：出包时指定并由 APP 作为常量保存；请求 Header 字段名为 `Gameid`。

说明：`accessToken` 与 `loginToken` 不存在，统一采用 `accountToken`。JSON/Bridge 数据字段使用 `packageCode`、`gameId` 等驼峰命名；HTTP Header 按本需求第 4.5 节列出的名称发送。HTTP Header 名称大小写不敏感。

## 3. 模块划分

### 3.1 SDK Core

- 初始化流程
- 状态机
- 配置解析
- 生命周期管理
- 线程调度

### 3.2 Network

- 网络模块初始化
- 心跳
- 角色上报
- 其他原生网络请求

### 3.3 Web Container

- WebView 创建
- 页面路由
- 弹窗尺寸与定位
- 关闭管理
- WebView 实例按用途拆分并单独命名：`web_login`、`web_agreement`、`web_welfare`、`web_game`；实例名表示页面用途，不代表域名边界
- 初始化接口域名固定硬编码为 `https://box.74meng.com`，接口路径固定为 `/h5_app/init`，完整地址为 `https://box.74meng.com/h5_app/init`；不由初始化响应下发
- 当前初始化响应中的 `h5Pages` 页面均使用 `box.74meng.com`；`jumpDomain` 仅用于校验游戏内跳转到外部浏览器的目标主机，示例还包含 `ipay.74meng.com`
- `jumpDomain` 不用于 JSBridge 来源校验；Bridge 来源信任与外链跳转校验是独立安全策略
- 外链校验对 `http`、`https` 均允许，按 URL 解析后的主机名与白名单精确匹配，不自动放行子域；校验通过后才允许交由外部浏览器打开

### 3.4 JS Bridge

- H5 与 SDK 的双向调用
- 参数校验
- 响应与错误处理

### 3.5 Auth

- `accountToken` 安全存储
- 登录状态管理
- 退出登录（清除 `accountToken`）

### 3.6 Update

- 更新弹窗
- 下载进度
- APK 安装唤起
- 是否更新由服务端决定

### 3.7 小窗口弹窗

- 显示、隐藏、定位
- 与其他 H5 页面的联动（互相唤起、关闭等）
- 初始化配置中列出多个 H5 页面，每个页面含窗口实例名称（如 `web_login`、`web_agreement`、`web_welfare`），按实例名打开/关闭

### 3.8 悬浮球

- 用户登录成功且 `web_game` 加载后显示在游戏页面上层；福利大厅位于游戏上方时隐藏，大厅关闭或游戏重新置顶后恢复
- 初始化配置与运营心跳控制显隐、激活状态、图标和位置尺寸
- 支持垂直方向拖拽
- 点击唤起福利大厅

## 4. 核心业务流程

### 4.1 启动与初始化

1. 启动应用后，请求硬编码地址 `https://box.74meng.com/h5_app/init`。域名与路径是客户端常量，不能从初始化接口响应中获取或被响应覆盖。
2. 初始化接口返回统一的 `code`、`msg`、`data` 结构；`data` 包含更新、一键登录、运营弹窗轮询、跳转域名白名单、悬浮球、隐私配置和 H5 页面配置。
3. 当前接口示例中，登录、协议、福利和游戏页面都位于 `box.74meng.com`；`jumpDomain` 另行列出 `ipay.74meng.com` 和 `box.74meng.com`。业务页与游戏页可能跨域的旧描述不适用于这份示例配置。
4. 启动流程、初始化响应解析和页面创建关系见下方数据示例与字段说明。当前已知的接口契约以示例为准，尚未提供的请求方法、请求参数和异常响应仍需确认。

#### 初始化接口地址

```text
https://box.74meng.com/h5_app/init
```

该完整地址为 SDK 硬编码配置。若未来需要切换环境，应通过代码构建配置或版本发布管理，不通过本接口响应修改自身地址。

#### 初始化接口响应示例

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "updateInfo": {
      "open": 0,
      "width": "50",
      "height": "auto",
      "popup": 1,
      "force": 0,
      "title": "",
      "time": "",
      "desc": "",
      "version": "",
      "url": ""
    },
    "jumpDomain": [
      "ipay.74meng.com",
      "box.74meng.com"
    ],
    "aliyunOnekeyConfig": {
      "open": 0,
      "config": {
        "screen_code": 1,
        "launch_total_timeout_ms": 5000,
        "launch_attempt_timeout_ms": 2000,
        "launch_max_attempts": 2,
        "launch_retry_interval_ms": 500,
        "scheme_secret": "",
        "exchange_url": "https://box.74meng.com/h5_app/login_by_onekey",
        "app_protocols": [
          {
            "name": "《用户协议》",
            "url": "https://box.74meng.com/h5_app/privacy?sid=1&gid=266"
          },
          {
            "name": "《隐私政策》",
            "url": "https://box.74meng.com/h5_app/privacy?sid=1&gid=266"
          }
        ],
        "ui": {
          "title": "手机快捷登录",
          "login_text": "手机号一键登录",
          "other_login_text": "使用其他方式登录",
          "primary_color": "#CC0000",
          "dialog_width_percent": 60,
          "dialog_height_percent": 75
        }
      }
    },
    "popupInterval": {
      "interval": 30,
      "url": "https://box.74meng.com/h5_app/popup_interval"
    },
    "floatball": {
      "show": true,
      "image_default": "https://pic.yee5.com/wapupload/receipt/20260803/2026080308456226.png",
      "image_active": "https://pic.yee5.com/wapupload/receipt/20260803/2026080308461747.gif",
      "width": 36,
      "height": 36,
      "right": 10,
      "bottom": 200
    },
    "privacyAuto": true,
    "h5Pages": {
      "agreement": {
        "instance": "web_agreement",
        "url": "https://box.74meng.com/h5_app/agreement",
        "width": "60%",
        "height": "75%",
        "left": "center",
        "top": "center"
      },
      "login": {
        "instance": "web_login",
        "url": "https://box.74meng.com/h5_app/login",
        "width": "60%",
        "height": "75%",
        "left": "center",
        "top": "center"
      },
      "welfare": {
        "instance": "web_welfare",
        "url": "https://box.74meng.com/h5_app/welfare",
        "width": "100%",
        "height": "100%",
        "left": 0,
        "top": 0
      },
      "game": {
        "instance": "web_game",
        "url": "https://box.74meng.com/h5?gid=266&sid=1&cid=1863&app=1&xid=0",
        "width": "100%",
        "height": "100%",
        "left": 0,
        "top": 0
      }
    }
  }
}
```

#### 初始化响应字段说明

| 字段 | 类型 | 当前约定与用途 |
| --- | --- | --- |
| `code` | number | 顶层请求结果码；`0` 表示成功；非 `0` 时停止初始化，在页面显示“游戏初始化失败：”加服务端 `msg`，不继续隐私、登录和游戏流程 |
| `msg` | string | 顶层结果说明，成功示例为 `success`；初始化失败时用于页面提示，失败响应不要求包含 `data`；缺失或空白时回退显示初始化接口返回的错误码 |
| `data.bgImage` | string | 可选的启动背景图绝对地址；为空或缺失时不设置底层背景；有值时使用等比例 `centerCrop` 全屏铺底，所有 H5 WebView 透明叠加在其上 |
| `data.updateInfo` | object | 在线更新配置，字段见第 8 节 |
| `data.jumpDomain` | string[] | 游戏内跳转到外部浏览器的目标主机白名单；精确匹配主机名，允许 `http`、`https`，不自动匹配子域；不作为 Bridge 来源校验 |
| `data.aliyunOnekeyConfig` | object | 阿里云一键登录开关和配置；`open=1` 时先尝试一键登录，`open=0` 时跳过一键登录并进入 H5 登录 |
| `data.popupInterval` | object | 运营弹窗轮询配置；`interval` 按秒解释，`url` 为心跳 POST 请求地址；响应外层为 `code/msg/data` |
| `data.floatball` | object | 悬浮球初始显示状态、图片和位置尺寸配置 |
| `data.privacyAuto` | boolean | 本地尚未同意时，`true` 打开 `h5Pages.agreement`，`false` 跳过并本地记为已同意；本地已同意时忽略该字段 |
| `data.h5Pages` | object | H5 页面实例映射；每项提供 `instance`、`url`、`width`、`height`、`left`、`top` |

#### `h5Pages` 页面映射

| 配置键 | 实例名 | 用途 | 示例 URL |
| --- | --- | --- | --- |
| `agreement` | `web_agreement` | 用户协议/隐私相关页面 | `https://box.74meng.com/h5_app/agreement` |
| `login` | `web_login` | 登录页 | `https://box.74meng.com/h5_app/login` |
| `welfare` | `web_welfare` | 福利大厅 | `https://box.74meng.com/h5_app/welfare` |
| `game` | `web_game` | 游戏主页面 | `https://box.74meng.com/h5?gid=266&sid=1&cid=1863&app=1&xid=0` |

`openMiniWindow` 和 `closeMiniWindow` 使用配置中的实例名（例如 `web_welfare`）；路由键（例如 `welfare`）与实例名是两个不同字段。百分比尺寸相对于当前屏幕方向下的可用视口；数字坐标按 dp 处理，`center` 表示居中。

### 4.2 隐私协议

- 本地已保存同意状态时，启动直接跳过隐私页，忽略本次 `privacyAuto` 值。
- 本地未保存同意状态时：`privacyAuto=true` 打开 `h5Pages.agreement`（实例名 `web_agreement`），等待用户调用 `agreePrivacyPolicy`；调用成功后永久保存同意状态并继续流程。`privacyAuto=false` 不打开页面，将本地状态保存为已同意并继续流程。
- H5 调用 `rejectPrivacyPolicy` 时，将本地同意状态改为不同意并退出当前流程；之后再次启动时重新按 `privacyAuto` 处理。
- 隐私协议页 JS 交互：
  - 点击“拒绝”：调用 `rejectPrivacyPolicy`，不要求弹出额外提示。
  - 点击“同意”：调用 `agreePrivacyPolicy`；状态保存在应用本地，直至用户后续明确拒绝或卸载应用。

### 4.3 在线更新判断

已同意隐私协议时，根据初始化接口返回的在线更新相关字段，判断是否弹出更新窗口。

### 4.4 用户登录

一键登录的完整跨平台需求、最新视觉尺寸、接口与 iOS 参考事项见[阿里云一键登录独立需求](docs/阿里云一键登录独立需求_aliyun-onekey-login.md)。

隐私协议处理和在线更新判断完成后，按以下顺序执行登录：

1. 若本地已保存 `accountToken`，优先 POST `/h5_app/login_by_token`，并在 `Accountoken` Header 中携带本地 Token。
2. 本地 Token 自动登录、阿里云一键登录兑换共用以下响应格式：

```json
{"code":0,"msg":"登录成功","data":{"token":"abc123..."}}
```

   `code=0` 表示成功，原生从 `data.token` 读取非空字符串并覆盖本地 `accountToken`，继续进入 `web_game`；任意 `code!=0` 均清除本地 `accountToken` 并完成持久化，后续 Header `Accountoken` 与 GameInfo/Bridge 中的 Token 均为空，再继续后续登录方式。期间未通过其他方式重新登录成功时，重启应用不得再次请求 Token 登录。网络异常未收到有效业务响应时保留旧 Token 并继续后续登录方式；`code=0` 但缺少有效新 Token 时不视为成功。
3. Token 自动登录未成功时，若 `aliyunOnekeyConfig.open=1`，展示阿里云号码认证弹窗。原生从初始化配置读取 `scheme_secret` 并传给 SDK 的 `setAuthSDKInfo`。用户授权后原生取得 `auth_token`，POST 到初始化配置 `aliyunOnekeyConfig.config.exchange_url` 指定的地址。兑换成功按统一响应格式保存 Token 并进入 `web_game`；取消、SDK 或接口失败均继续 H5 登录。`exchange_url` 是配置字段，不是固定接口路径；当前示例值为 `/h5_app/login_by_onekey`。
4. `open=0` 或前述方式均未成功时，打开 H5 登录页 `h5Pages.login`（实例 `web_login`）。H5 自行处理登录；成功后调用 `notifyLoginSuccess({ accountToken: "xxxx" })`，原生安全保存 Token 并打开 `web_game`。
5. `requestPhoneLogin` 仅负责唤起授权弹窗，仅在 `aliyunOnekeyConfig.open=1` 时可启动。SDK 确认展示成功后立即且仅回调一次 `{code:0,msg:"唤起成功",data:true}`；先完成 JS 回调再关闭 `web_login`。不等待认证或兑换，也不返回 Token。后续关闭、切换或失败由原生恢复新 H5 登录窗口，兑换成功才保存 Token 并进入游戏，不再回调原请求。展示前失败返回 `3001` 并保留当前登录页；`open=0` 返回 `3003`，不得绕过开关。

一键登录使用仓库资料：[`numberAuthSDK_APP_Android_v2.14.24_operator_ui_log_static.zip`](refs/numberAuthSDK_APP_Android_v2.14.24_operator_ui_log_static.zip)，压缩包内 AAR 为 `auth_number_product-2.14.24-log-online-standard-cuum-release.aar`；本地接入说明见 [`安卓一键登录集成文档.txt`](refs/安卓一键登录集成文档.txt)，其中提供[阿里云 Android 客户端接入文档](https://help.aliyun.com/zh/pnvs/developer-reference/the-android-client-access)。SDK 由 Android 原生层集成和调用，H5 通过既有 JSBridge 参与登录流程。

`aliyunOnekeyConfig.open=1` 时，`config` 用于配置号码认证方案、弹窗方向、尺寸、协议和 UI：

| 配置字段 | 约定 |
| --- | --- |
| `scheme_secret` | 阿里云号码认证方案密钥；不得写入日志或普通调试输出 |
| `launch_total_timeout_ms` | 传入 SDK `getLoginToken` 的总超时，单位毫秒；当前线上为 `5000` |
| `launch_attempt_timeout_ms` / `launch_max_attempts` / `launch_retry_interval_ms` | 当前接口仍下发，但客户端尚未解析和实现单次超时、重试次数与间隔控制；不得视为已生效 |
| `screen_code=1` | 横屏弹窗模式 |
| `screen_code=2` | 竖屏弹窗模式 |
| `ui.dialog_width_percent` | 弹窗宽度，按 `screen_code` 对应方向的可用视口百分比设置 |
| `ui.dialog_height_percent` | 弹窗高度，按 `screen_code` 对应方向的可用视口百分比设置 |
| 弹窗背景 | `#eeeeee`，圆角约 14dp；弹窗外为黑色 45% 遮罩 |
| 品牌头部 | `#c52d28`，在原高度上增高 15%：含弧线总高度 43.7dp，红色主体参考高度 34.5dp；等比缩放 `box_logo.png`；关闭图标在红色主体垂直居中，右距约 10dp |
| `ui.title` | 忽略，不展示默认文字标题 |
| `ui.login_text` | 登录按钮文字；按钮采用参考图的橙底白字 |
| 协议详情页标题栏 | `#c52d28`，白色返回按钮和标题文字 |
| 一键登录弹窗和 H5 页面转场 | 打开淡入、关闭淡出，建议时长 200ms；动画不阻塞登录状态切换 |

弹窗宽高按 `screen_code` 对应方向使用配置中的百分比，不在客户端另设固定尺寸。`config.app_protocols` 展示在号码认证弹窗内的协议链接；它与启动流程中的 `h5Pages.agreement` 隐私协议页面用途不同。

H5 登录页、协议页、福利页和游戏页的打开/关闭均使用约 200ms 淡入淡出效果。取消一键登录或登录失败切换到 H5 登录页时，关闭认证弹窗并淡出，随后 H5 登录页淡入；动画不得阻断登录回退流程。

### 4.5 游戏主 WebView

- 登录成功后打开游戏 WebView（本质为封装 H5 页面的壳），并销毁 H5 登录实例 `web_login`。Token 自动登录、阿里云登录和 `notifyLoginSuccess` 成功均使用同一切换流程。
- 游戏存在时打开福利大厅 `web_welfare`，将大厅置顶，保留游戏 WebView 及其当前文档状态。同名大厅已存在时复用置顶，不重复创建，也不关闭游戏。
- APP 使用沉浸式全屏：隐藏状态栏、导航栏以及电池和信号等系统工具栏；H5 页面实例、悬浮球和一键登录弹窗的尺寸与定位均基于隐藏系统栏后的完整设备视口计算。
- 建议最低支持 Android API 23（Android 6.0）；Web 内容使用设备当前稳定版 Android System WebView，不在应用中固定 Chromium 内核版本。验收覆盖 Android 8、10、13、15，并记录设备系统与 WebView 版本。
- H5 游戏页面可通过 JS 从游戏端获取 `accountToken`，并在服务器端处理。
- `getGameInfo` 返回 APP 本地保存的游戏信息：`packageCode`、`gameId`、`gameVersion`、`accountToken`、`deviceType`、`deviceCode`。`packageCode` 未读取成功时为空字符串；`deviceType` 本期固定为 `android`；用户尚未同意隐私协议时 `deviceCode` 为空，同意后按第 4.6 节生成并返回。其余字段按出包配置或登录状态返回。
- `changeGameUrl({url})` 用于切换 `web_game`：URL 必须是有效绝对 HTTP/HTTPS 地址，主机名与初始化 `h5Pages.game.url` 的主机名精确匹配，不自动允许子域，且不使用仅服务于外部浏览器跳转的 `jumpDomain`。已有 `web_game` 时在原实例中导航到新 URL 并置顶；不存在时创建、加载该 URL 并置顶。地址校验通过且原生接受导航操作时返回 `data:true`，参数/地址校验或原生导航操作失败时返回 `data:false`；`true` 不代表远端页面加载成功。
- 福利大厅调用 `changeGameUrl` 时，直接在现有游戏 WebView 中加载新地址，不关闭、销毁或重建游戏实例；新文档会替换旧文档，旧页面 JS 状态不保留。大厅保留在游戏后方，游戏置顶后恢复运行时悬浮球状态，下次打开大厅继续复用大厅实例。
- 初始化、游戏及相关 SDK 服务端请求由原生网络层统一附加以下 HTTP Header；无可用值时仍发送该 Header，值为空字符串（尤其未同意隐私协议时 `Devicecode` 为空）：

| HTTP Header 名称 | 来源 |
| --- | --- |
| `Gameid` | 本地常量 `gameId` |
| `Devicecode` | 本地设备码 `deviceCode`；未同意隐私协议时为空 |
| `Packagecode` | APK 渠道码 `packageCode`；未获取时为空 |
| `Devicetype` | `deviceType`，本期固定 `android` |
| `Accountoken` | 安全存储的 `accountToken`；未登录时为空 |
| `Gameversion` | 本地常量 `gameVersion` |

上述六个 HTTP Header 仅要求用于初始化 `POST /h5_app/init`、运营心跳 `/h5_app/popup_interval`、阿里云一键登录兑换接口、本地 Token 自动登录 `POST /h5_app/login_by_token`。隐私同意前，初始化请求的 `Devicecode` 与 `Accountoken` 均发送空字符串；同意后本地 Token 自动登录时 `Accountoken` 携带本地 Token。其他业务请求 Header 另按接口契约约定。

HTTP Header 名称大小写不敏感，原生按表中拼写发送。PHP 的 `$_SERVER` 通常映射为 `HTTP_GAMEID`、`HTTP_DEVICECODE`、`HTTP_PACKAGECODE`、`HTTP_DEVICETYPE`、`HTTP_ACCOUNTOKEN`、`HTTP_GAMEVERSION`。例如 `$deviceCode = $_SERVER['HTTP_DEVICECODE'] ?? '';`。`$server['Devicecode']` 仅在项目自身将 Header 归一化到 `$server` 数组时才适用；建议使用 `getallheaders()` 或框架 Header API 按不区分大小写方式读取。
- 验证 Token 失败时：关闭游戏 H5 页面，重新走一遍登录流程。
- H5 退出接口：清除本地持久化 `accountToken`，销毁游戏、福利大厅和运营弹窗等当前会话页面，隐藏悬浮球，然后从一键登录步骤重新开始。初始化 `aliyunOnekeyConfig.open=1` 时尝试阿里云授权及业务兑换；未开启、配置不足、用户取消或认证/兑换失败时，创建新的 H5 登录实例 `web_login`。不复用旧登录文档，不再尝试旧 Token 自动登录，也不重跑初始化和更新检查。任一方式重新登录成功后保存新 Token 并创建新的游戏实例。退出登录不会清除 `saveLocalStorage` 保存的数据和启动隐私同意状态；`logout` 成功回调只表示退出流程已受理，不表示重新认证成功。
- 实例从容器关闭时，其 Bridge 立即失效；即使仍在执行淡出动画，旧页面后续请求也不能操作窗口或新会话。同名实例重建后仍须校验 WebView 对象身份。

### 4.6 设备码生成与隐私

- 用户未同意隐私协议前，不读取 OAID 或 `ANDROID_ID`，不生成 DIY 设备码；本地运行态 `deviceCode` 为空，`getGameInfo` 和请求 Header 均为空值。
- 用户同意后按以下优先顺序生成设备码：有 OAID 时取 `MD5(OAID)` 并转大写；否则有 `ANDROID_ID` 时取 `MD5(ANDROID_ID)` 并转大写；两者均不可用时生成随机 DIY 设备码，计算 `MD5(DIY设备码)` 并保留小写。
- MD5 输入使用 UTF-8 字节，输出为不含分隔符的 32 位十六进制字符串。不得直接使用或上传 IMEI、MAC 地址、硬件序列号、原始 OAID 或原始 `ANDROID_ID`。
- DIY 设备码生成后加密保存于应用私有持久化存储；后续启动始终复用该设备码，不因 OAID/`ANDROID_ID` 后来变为可用而替换。应用卸载重装或应用数据被清除后，按流程重新取得或生成设备码。
- 设备码仅在隐私同意状态有效时对 H5 返回并用于请求；隐私同意状态无效时按空值处理。

### 4.7 游戏角色信息

- `saveGameRole` 参数不变，将 H5 提交的角色资料保存到应用本地；以 `gameId` 为唯一索引，每个游戏仅保存一份角色资料，可以同时保存不同游戏的数据。
- `gameId`（整数）和非空 `roleId`（字符串）仍为保存时的必填参数。任一缺失或无效时忽略本次写入并返回 `data:false`。
- `roleName`、`serverId`、`serverName`、`account` 为字符串；`roleLevel`、`rolePower` 为整数。字段可选；仅写入存在且类型正确的字段，未知字段或类型不匹配字段丢弃。
- 同一 `gameId` 再次保存时全量替换原角色资料，即使 `roleId` 不同也覆盖旧记录；未提交或类型无效的可选字段不保留旧值，其他游戏的数据保留。写入成功返回 `data:true`；本地写入失败返回 `data:false`。
- `getGameRole` 参数为 `{gameId:266}`，仅按 `gameId` 查询，无需 `roleId`；存在时返回已存储的角色字段对象，无匹配记录、索引无效或读取失败时返回 `data:false`。
- 游戏页面 `web_game` 的 H5 主文档每次加载成功后，清空本地存储的所有游戏角色信息，包括首次加载、普通刷新和 `changeGameUrl` 重新加载。仅接受导航、加载中或加载失败时不清空；登录页、福利页等其他页面加载不触发清理，也不清理 `saveLocalStorage` 的数据。H5 在本次加载成功后重新提交角色资料，保存前 `getGameRole` 返回 `data:false`。
- 以上方法统一使用 JSBridge `Result` 包装；布尔值放在 `data` 中。角色资料保存在应用私有本地存储中，按上述游戏页加载成功时机清理。

### 4.8 运营弹窗心跳

- 登录成功、`web_game` 加载可见且悬浮球实际显示后，按初始化响应 `popupInterval` 中的 `url` 和 `interval` 启动周期请求。示例 URL 为 `https://box.74meng.com/h5_app/popup_interval`，`interval` 示例值为 `30`。首次请求等待完整 `interval` 秒，不立即请求，也不固定延迟 1 秒；缺失 URL、非法 URL 或非正间隔不启动轮询。
- 心跳请求携带第 4.5 节定义的六个固定 Header；未登录或未同意隐私时对应值为空字符串。
- 请求使用 POST、JSON 请求体 `{}`；响应外层 `code != 0` 完全忽略，不改变悬浮球或窗口。`code = 0` 时读取以下数据：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "popup_pages": [],
    "floating_ball": {
      "show": true,
      "active": false
    }
  }
}
```

- `popup_pages` 单项字段：

| 字段 | 说明 |
| --- | --- |
| `instance` | H5 窗口实例名；相同实例已存在时不重复创建或加载 |
| `url` | H5 页面地址；按页面/实例允许的加载策略校验，不将 `jumpDomain` 用作 Bridge 来源校验 |
| `width`、`height` | 窗口宽、高 |
| `left`、`top` | 窗口距左侧、顶部的位置参数 |

- `floating_ball.show` 和 `floating_ball.active` 按 `setFloatingBall` 的规则控制显隐和图标；状态未变化时不重新加载图标。
- 收到 `popup_pages` 数组时，逐项处理其中的 H5 窗口配置。已存在相同 `instance` 的窗口不重复创建、重载或置顶；即使 URL 或尺寸变化也忽略；不存在的实例创建并置顶，允许同时显示多个窗口。
- `popup_pages` 每项包含 `instance`、`url`、`width`、`height`、`left`、`top`；其顺序决定层级，后项显示在上层。动态窗口 URL 按页面加载安全策略校验；`jumpDomain` 仅用于游戏内跳转到外部浏览器。
- 响应包含 `popup_pages` 时，将其作为“运营弹窗”清单，只关闭已标记为运营弹窗且不在清单中的窗口。初始化 `h5Pages` 中所有功能实例及其他非运营窗口都不受影响，心跳不能借用功能实例名创建运营窗口。空数组关闭全部运营弹窗；字段缺失时不做清单同步。
- 心跳下发的运营页面安装 JSBridge，信任限定在下发 URL 的初始主机；原生消息入口校验来源和主 frame，拒绝子 frame 和其他主机。普通动态窗口不会因此获得权限。不支持 `WEB_MESSAGE_LISTENER` 的旧系统 WebView 仍可显示运营页面，但不授予不安全的原生 Bridge，需更新 Android System WebView。
- 退出登录、`web_game` 关闭或宿主销毁后停止任务，旧定时任务及已排队响应不得作用于新会话；宿主销毁还释放调度线程。已发送的 HTTP 不保证立即中断，但其返回结果失效。下次登录和显示球后重新等待完整间隔；新会话恢复初始化球状态。
- 每次请求完成后按 `interval` 秒安排下一轮，不并发叠加请求。网络、HTTP 或格式失败不改变当前状态，下一周期继续请求，不额外并发重试。心跳已启动后，服务端隐藏球或福利页临时遮挡球不会停止轮询，后续响应仍可恢复显示。
- `data: []` 或缺失业务字段不更新状态；`popup_pages` 缺失不做清单同步，明确的空数组关闭全部运营弹窗。

## 5. 悬浮球与福利大厅

- **创建与显隐：** 登录完成且 `web_game` 实例加载后，才创建并显示悬浮球；初始化配置 `floatball.show=false` 时隐藏，`true` 时允许显示。后续心跳或 H5 调用可以更新运行时 `show` 状态。福利大厅 `web_welfare` 位于游戏上方时强制临时隐藏；大厅关闭或游戏重新置顶后恢复运行时悬浮球状态，大厅保留在游戏后方不影响显示。游戏实例关闭或退出登录后不显示。
- **激活与图标：** 默认 `active=false`，使用 `floatball.image_default`；`active=true` 使用 `floatball.image_active`。运行中的显隐和激活状态可由 JSBridge 或心跳响应更新。
- **尺寸和位置：** 初始化响应 `floatball` 当前包含 `show`、`image_default`、`image_active`、`width`、`height`、`right`、`bottom`。位置支持 `left`/`right` 与 `top`/`bottom`，表示距屏幕对应边缘的间距；同一方向的两侧同时提供时，优先 `left`、`bottom`，忽略 `right`、`top`。示例使用 `right=10`、`bottom=200`。
- `width`、`height` 和位置偏移为正整数，默认单位为像素；建议以 360dp 屏幕宽度为设计基准，结合设备密度换算为 dp，并避让状态栏、手势区等安全区域。
- 用户可垂直拖动悬浮球；点击后在游戏页面上层打开福利大厅。多个动态弹窗按心跳 `popup_pages` 数组顺序叠放，后项位于上层。
- 图标或显隐状态未改变时不得重复加载图标；仅在图标确实变化时更新图像，避免重载闪动。位置或尺寸变化应更新布局，不应仅因此重载图像。
- 初始化的 `floatball.show` 是初始状态；后续心跳和 H5 `setFloatingBall` 更新运行状态。福利大厅打开和游戏页关闭属于生命周期约束，分别临时隐藏及停止显示。

## 6. 出包脚本（Python）

本项目交付 Windows 桌面 GUI 出包工具，供运营人员基于同一 Android 工程模板为多个游戏生成独立签名的母包。建议用 Python + Tkinter 制作表单，负责编排参数校验、资源复制与 Gradle 构建；Android Gradle 工程负责实际构建和签名，Python 不直接修改 APK 内容。Android Gradle 工程及通用模板属于本项目需开发的交付内容，不要求用户预先提供。

### 6.1 脚本需指定参数

- 游戏 ID（`gameId`）
- 游戏版本号（`gameVersion`）
- APP 桌面名称
- Android 包名（`applicationId`）
- 签名证书文件路径及证书口令
- PNG 桌面图标文件路径
- 启动闪图资源文件夹路径
- 输出目录

### 6.2 使用方式要求

- GUI 表单输入 `gameId`、`gameVersion`、APP 桌面名称、Android 包名（`applicationId`）；GUI 选择签名证书文件、PNG 图标文件、闪图资源文件夹和输出目录。签名 alias/password 等按证书格式要求输入。
- 证书密码在构建时安全输入，仅保留在当前进程内，不写入配置模板、日志或源码；不同游戏可选择不同证书。
- 支持保存不含签名密码的游戏配置，便于下次重复出包；执行前校验参数、证书和资源，并展示 Gradle 构建进度、错误信息及输出 APK 路径。
- 图标文件由工具转换为 Android 所需的多密度资源；闪图目录按约定的资源名称、方向和尺寸映射至 Android 资源目录。
- 母包输出为已签名 APK，文件名为 `<Android包名>_<游戏版本>.apk`；同时输出同名 `.txt` 安装包信息文档，包含游戏名称、游戏 ID、游戏版本、包名、签名证书的 MD5 指纹（供阿里云一键登录配置使用，32 位小写十六进制且不含冒号）和生成时间。“包签名”从实际 APK 经 `apksigner verify --print-certs` 验证后读取，不使用 APK 文件内容 MD5；签名验证或指纹读取失败时报告构建失败，不覆盖已有成品。版本字段统一取本次 GUI 的 `gameVersion`。
- 之后如需渠道发行，再使用独立渠道分包工具写入渠道码，分包不重新签名。

### 6.3 待确认

- 图标输入格式已定为 PNG；闪图通过 GUI 选择资源文件夹。
- 项目需新建 Android Gradle 工程与多游戏共用模板；模板及 GUI 的构建编排是本项目开发任务，不是外部前置条件。
- 闪图文件夹内部的方向命名、尺寸规格、文件映射和缺失资源处理，以及 `versionCode`、最低 Android 版本、屏幕方向等额外构建参数，仍需在工程设计时确定。

## 7. 渠道码（分包）

- 游戏包签名完成后，由单独的 Python 分包脚本将渠道码写入安装包。
- 渠道码示例：`1-25-6#10028`。
- 渠道分包应以已签名母包为输入；母包命名按第 6.2 节规则生成，分包工具不得修改 APK 签名。
- 正常安装后，H5 通过 JS 获取游戏相关信息时，返回的 `data.packageCode` 即该渠道码。
- 游戏启动时从安装 APK 的指定目录读取渠道码，在本次会话内存保存，并写入 HTTP Header `Packagecode`。
- 未写入、读取失败或格式无效时，HTTP Header `Packagecode` 与 `data.packageCode` 返回空字符串，不影响其他业务流程。

## 8. 更新下载与安装

更新判断使用初始化响应 `data.updateInfo`。2026-10-28 更新契约统一采用 `open`、`width`、`height`、`popup`、`force`、`title`、`time`、`desc`、`version`、`url`，取代旧版 `isUpdate`、`forceUpdate`、`downloadUrl`、`isPop`。完整初始化接口字段见 [初始化接口字段说明](docs/初始化接口字段说明_initialization-api.md)。

隐私流程结束后、登录前检查更新。必须先判断 `open`：`open=0` 时忽略其余更新字段，不校验、不弹窗，直接继续登录；`open=1` 时才进入更新流程。正常更新弹窗中，`force=1` 表示强制更新且不显示关闭按钮，`force=0` 时允许关闭。强制更新只约束正常弹窗的关闭能力，不改变异常容错规则。

### 8.1 更新弹窗内容

- 弹窗由 Android 原生视图绘制，使用用户提供的 `bg.png` 作为头部背景，保持头图比例；窗口白底、圆角按 Android 15dp 适配，显示效果参照用户提供的更新效果图。
- `width`：窗口宽度，占可用窗口宽度的百分比，例如 `"50"` 表示 50%。
- `height`：`"auto"` 时按内容自适应；数字或数字字符串按可用窗口高度的百分比计算。
- `title`：头部白色标题；`desc`：标题下方的红色更新说明；`version`、`time` 分别显示版本号和更新时间。
- 弹窗下方遮罩为 `#000000`，透明度 45%。绿色圆角按钮文案为“立即更新”。
- `force=1` 时不显示下方圆形关闭按钮，且正常状态下不可通过返回键关闭；`force=0` 时可关闭并继续启动。
- 点击遮罩不关闭；内容过长时在面板内部滚动，关闭控件保留在屏幕内。`title/time/desc/version` 允许空字符串，对应文案为空时隐藏。

### 8.2 更新处理与容错

- `open=1` 且配置有效时展示更新弹窗；`popup` 控制点击“立即更新”的处理方式，不控制弹窗显隐。
- `popup=1`：`url` 是外部更新页面的绝对 HTTP/HTTPS 地址，点击后通过外部浏览器打开。
- 外部页面打开成功时，`force=0` 结束更新并继续登录；`force=1` 保留原生更新窗口，用户返回游戏后仍可点击更新。
- `popup=0`：`url` 是可直接下载 APK 的绝对 HTTP/HTTPS 地址，点击后在应用内部下载，不跳浏览器；显示蓝绿渐变进度条及下载进度提示。
- 内部下载完成后，自动启动系统安装流程；进度条下方同时显示“立即安装”按钮，样式与“立即更新”一致。用户取消系统安装后仍可点击“立即安装”再次启动安装流程。
- Android 8 及以上安装未知来源应用需要系统授权；应用引导至本应用的安装授权页，获得授权后继续请求系统安装；安装确认由系统完成，不进行静默安装。
- `open=1` 时校验更新模式、强制开关、窗口尺寸、文案类型和 HTTP/HTTPS 地址。字段类型错误、URL 无效、下载或安装处理失败时，显示简短提示，终止本次更新并关闭更新窗口，随后继续登录流程。
- 上述异常容错对强制和非强制更新均适用；不得因更新异常阻塞启动。正常状态下由 `force` 决定是否允许用户关闭窗口。

### 8.3 强制与非强制

- 正常更新状态下，强制更新不显示关闭按钮；非强制更新允许关闭并继续后续流程。
- 更新处理异常时统一按容错策略提示、结束更新流程并继续登录，不因 `force=1` 改为阻塞。

## 9. JS Bridge 接口约定

H5 页面通过全局对象 `QSMH5APP` 与原生交互。接口范围包括：

- 隐私协议：`agreePrivacyPolicy`、`rejectPrivacyPolicy`。
- 登录与会话：`requestPhoneLogin`、`notifyLoginSuccess`、`getAccountToken`、`logout`。
- 游戏信息：`getGameInfo`。
- 游戏角色：`saveGameRole`、`getGameRole`。
- 本地存储：`saveLocalStorage`、`getLocalStorage`。
- 第三方应用：`isAppInstalled`、`openApp`，均接收 `{packageName}`，通过统一异步回调的 `data` 返回布尔值。检测可见且已安装的包为 `true`，未安装或系统不可见为 `false`；打开采用系统提供的包启动入口，系统接受启动请求为 `true`，未安装、无入口或系统拒绝为 `false`。参数无效返回 `code:1001,data:false`，其他异常返回 `code:9999,data:false`。
- 第三方应用能力仅供已注入 Bridge 的可信页面调用；打开应由 H5 按钮点击触发，不自动下载、安装或跳转商店。Android 11+ 声明 MAIN/LAUNCHER 包查询和 `com.me74.qsmhz`，不使用 `QUERY_ALL_PACKAGES`；其他无桌面入口的目标包须在 Manifest 的 `queries` 中显式添加后重新出包，不能保证任意隐藏包的检测准确性。应用安装信息的使用应纳入隐私披露，H5 不应批量枚举包名。
- 悬浮球与窗口：`setFloatingBall`、`openMiniWindow`、`closeMiniWindow`、`changeGameUrl`、`closePage`。

各方法的参数、回调和返回码见 [JSBridge 方法清单](JSBridge方法清单_jsbridge.md)。业务规则以本文为准；Bridge 签名、回调协议及错误码仍需 Android 与 H5 联调确认。

### 9.1 应用私有本地存储

- `saveLocalStorage`、`getLocalStorage` 允许所有 SDK 注入 Bridge 的可信 H5 页面调用，包括协议页、登录页、福利页、游戏页等，不要求已登录或已同意隐私协议；来源信任沿用 SDK 既有规则。
- 所有可信页面共享同一份应用内键值数据，不按域名、WebView 实例或账号隔离。使用独立的应用私有持久化存储，与浏览器 `window.localStorage` 独立；应用重启后仍保留，退出登录不会清除，卸载应用后由 Android 清除。
- `saveLocalStorage` 支持单一设置 `{key:"abc",value:"123"}` 和批量设置 `[{key:"abc",value:"123"},{key:"def",value:"456"},{key:"xxx",value:"000"}]`。每项按 `key` 写入 `value`；同名 key 覆盖旧值，同一批次重复 key 以最后一项为准，未传入的 key 保留原值，空数组不修改数据。不再使用旧版 `{abc:123}` 参数结构。
- 每项 `key` 必须为字符串，支持空字符串 key。缺失或类型错误、数组元素不是键值对象时返回 `code:1001`，整批不写入。`value` 字符串原样保存，数字按字符串保存，布尔值、对象、数组、`null`、`undefined` 等非字符串/数字值及缺失的 `value` 按空字符串保存，不删除 key。
- `saveLocalStorage` 持久化成功返回 `Result.data:true`；写入失败返回 `data:false`，异常沿用通用错误码。
- `getLocalStorage` 参数为 `{key:String}`，key 必填且必须为字符串，支持空字符串 key；缺失或类型错误返回 `code:1001`。`Result.data` 固定为字符串，直接返回保存的值；key 不存在时成功返回空字符串。参数错误或读取异常时 `data` 仍为空字符串，具体错误通过 `code`、`msg` 判断。

## 10. 接口与数据结构

初始化接口地址及响应示例已在第 4.1 节记录。服务端其他接口尚未形成完整数据契约；初始化接口仍需补充请求方法、请求参数、鉴权方式、错误码和超时策略。另需定义以下接口的请求与响应结构：

- 初始化接口：H5 页面地址与域名、窗口实例配置、在线更新信息、悬浮球状态及福利大厅参数。
- 登录验证接口：`auth_token` 输入、`accountToken` 输出及登录失败处理。
- 运营心跳与角色上报接口：请求时机、字段、Header、悬浮球状态更新和失败重试规则。

## 11. 关键字段速查

| 字段 | 说明 |
| --- | --- |
| `accountToken` | 登录令牌，登录成功后返回，本地缓存，供 H5 获取 |
| `auth_token` | 阿里云一键登录授权凭证，用于服务端验证 |
| `packageCode` | 渠道码，从安装包读取；HTTP Header 为 `Packagecode` |
| `gameId` | 出包指定并本地常量保存；HTTP Header 为 `Gameid` |
| `gameVersion` | 出包指定并本地常量保存；HTTP Header 为 `Gameversion` |
| `deviceCode` | 隐私同意后生成或取得；HTTP Header 为 `Devicecode` |
| `deviceType` | 客户端类型，本期固定 `android`；HTTP Header 为 `Devicetype` |
| `accountToken` | 安全存储的登录凭证；HTTP Header 为 `Accountoken` |
| 初始化接口地址 | SDK 内硬编码为 `https://box.74meng.com/h5_app/init`，不是初始化响应字段 |

## 12. 待确认问题

以下事项是仍需接口方或联调双方补齐的契约，不包含已定稿的业务口径和实现建议。

| 优先级 | 待确认事项 | 需要明确的内容 |
| --- | --- | --- |
| P0 | 初始化接口请求约定 | 请求参数、鉴权、超时、非 `code=0` 的处理、字段缺失/新增兼容；方法为 POST，部署地址固定为 `https://box.74meng.com/h5_app/init` |
| P0 | JSBridge 调用约定 | Android WebView 的桥接注入方式、参数序列化、异步回调形式、回调超时/重复调用处理、页面销毁后的回调行为及错误码；最低 API 与 WebView 版本建议见第 4.5 节 |
| P0 | 登录与 Token 生命周期 | 一键登录取消/失败、H5 登录回传、Token 本地保存与有效期、本地 Token 登录任意非 0 业务码清理及重启跳过、失效或刷新通知、并发窗口登出，以及 Token 不进入日志等处理 |
| P0 | 页面与窗口规则 | 页面实例名与 URL 映射、打开/关闭和置顶行为、尺寸定位、重复打开策略、`closeMiniWindow` 与 `closePage` 的适用范围及关闭结果定义 |
| P0 | 服务端接口契约 | 初始化、心跳、阿里云兑换、本地 Token 登录接口的请求字段、六个固定 Header 的接入验证、本地 Token 登录任意非 0 业务码清理、超时和重试规则；服务端部署已确认，兑换地址使用初始化配置 `exchange_url` |
| P1 | 域名与桥接安全实现 | 外链重定向后是否再次校验、非 HTTP(S) scheme 处理、Bridge 来源的独立信任策略及未通过时行为；`jumpDomain` 的用途和协议规则已定稿 |
| P1 | 更新实现补充 | 版本比较、下载中断与重试策略、存储空间不足和系统安装失败提示；更新异常放行规则已定稿 |
| P1 | 悬浮球状态优先级 | 初始化、心跳和 H5 同时更新时的优先级；建议按最后一次有效状态生效，福利大厅关闭后恢复最新运行状态 |
| P1 | 一键登录安全与错误映射 | SDK 初始化失败、凭证兑换失败的错误映射，以及 `scheme_secret` 的环境注入和安全存储；建议 `open=0` 时 `requestPhoneLogin` 返回 `3003` |
| P1 | 页面配置规则 | `width`/`height` 百分比参照物，`left`/`top` 数字和 `center` 的坐标规则，未知页面键、空 URL 及重复实例的处理 |
| P1 | 运营心跳接口契约 | 请求/响应外层结构、请求字段、失败退避策略；间隔单位、启动/停止时机、弹窗清单语义和层级顺序已定稿 |
| P1 | 初始化字段异常策略 | `privacyAuto`、`jumpDomain`、`popupInterval` 等字段缺失或类型错误时的统一降级策略 |
| P1 | 联调验收矩阵 | 网络异常、跨域页面、空/过期 Token、窗口切换、分包码缺失等场景及预期结果 |
| P1 | Windows GUI 出包规则 | 确认闪图目录内部的资源映射、缺图策略、额外 Gradle 构建参数和产物命名；Android Gradle 模板由本项目开发 |
