# JS Bridge 方法清单

## 文档信息

- 项目：qishimeng_h5_sdk
- 文档版本：v0.5
- 更新日期：2026-10-13
- 状态：方法名与参数为建议稿，待确认后定稿（命名空间已定为 QSMH5APP）

## 1. 概述与通用约定

### 1.1 命名空间

H5 页面通过全局对象 `QSMH5APP` 与原生层交互。

### 1.2 调用方向

- H5 → 原生：`QSMH5APP.方法名(参数, 回调)`。
- 原生 → H5：通过回调返回结果；如需主动推送，使用事件监听（见第 4 节）。

### 1.3 统一返回结构

所有异步方法通过回调返回 `Result` 对象：

```json
{
  "code": 0,
  "msg": "ok",
  "data": {}
}
```

- `code`：`0` 表示成功，非 `0` 表示失败。
- `msg`：提示信息，失败时说明原因，H5 可直接展示。
- `data`：业务数据，成功时返回，可为空对象。

### 1.4 调用示例

```js
QSMH5APP.getAccountToken({}, function (result) {
  if (result.code === 0) {
    console.log(result.data.accountToken);
  } else {
    console.log(result.msg);
  }
});
```

### 1.5 多域名与 WebView 实例

- 业务页面（登录、隐私协议、福利大厅）与游戏页面可能位于不同域名，均通过 `QSMH5APP` 与原生通讯；跨域不影响桥接调用，也不会产生阻塞。
- 原生按用途拆分并命名 WebView 实例：`web_login`、`web_agreement`、`web_welfare`、`web_game`。
- 游戏页面域名由出包配置指定，原生在桥接层做来源域名白名单校验，区分业务域名与游戏域名。

## 2. 方法清单总表

| 方法名 | 方向 | 分类 | 说明 |
| --- | --- | --- | --- |
| `agreePrivacyPolicy` | H5 → 原生 | 隐私协议 | 同意隐私协议 |
| `rejectPrivacyPolicy` | H5 → 原生 | 隐私协议 | 拒绝隐私协议，退出应用 |
| `requestPhoneLogin` | H5 → 原生 | 登录 | 唤起手机一键登录 |
| `notifyLoginSuccess` | H5 → 原生 | 登录 | H5 登录成功通知，回传 accountToken |
| `getAccountToken` | H5 → 原生 | 登录 | 获取本地缓存的 accountToken |
| `logout` | H5 → 原生 | 登录 | 退出登录，清理会话 |
| `getGameInfo` | H5 → 原生 | 游戏信息 | 获取游戏相关信息 |
| `setFloatingBall` | H5 → 原生 | 悬浮球 | 设置悬浮球状态（样式与显隐） |
| `openMiniWindow` | H5 → 原生 | 小窗口 | 打开指定 H5 页面窗口（按实例名） |
| `closeMiniWindow` | H5 → 原生 | 小窗口 | 关闭指定实例窗口 |
| `closePage` | H5 → 原生 | 页面 | 关闭当前窗口 |

## 3. 方法详细说明

### 3.1 隐私协议

#### `agreePrivacyPolicy`

- 说明：用户点击“同意”时调用，原生在应用内永久保存同意状态并继续后续流程。
- 参数：无。
- 返回：`Result`，`data` 为空对象。

```js
QSMH5APP.agreePrivacyPolicy({}, function (result) {
  // 成功后原生自动继续后续流程
});
```

#### `rejectPrivacyPolicy`

- 说明：用户点击“拒绝”时调用，直接退出应用，不弹任何提示。
- 参数：无。
- 返回：无（应用直接退出）。

```js
QSMH5APP.rejectPrivacyPolicy();
```

### 3.2 登录

#### `requestPhoneLogin`

- 说明：H5 登录页通过此方法重新唤起手机一键登录。唤起成功时原生隐藏 H5 登录页面，登录成功后返回 `accountToken`；唤起失败时返回提示 `msg`。
- 参数：
  - `scene`：可选，场景标识，默认 `"login"`。
- 返回（回调 `Result`）：
  - 成功：`data.accountToken` 为登录令牌。
  - 失败：`msg` 为失败原因，H5 登录页继续展示。

```js
QSMH5APP.requestPhoneLogin({ scene: "login" }, function (result) {
  if (result.code === 0) {
    // 唤起成功，原生已隐藏 H5 登录页，并缓存 accountToken
  } else {
    // 唤起失败，展示 result.msg
  }
});
```

#### `notifyLoginSuccess`

- 说明：H5 登录页登录成功后调用，将 `accountToken` 交给原生缓存并打开游戏 WebView。
- 参数：
  - `accountToken`：登录令牌，必填。
- 返回：`Result`。

```js
QSMH5APP.notifyLoginSuccess({ accountToken: "xxxx" }, function (result) {
  if (result.code === 0) {
    // 原生已缓存 accountToken 并打开游戏页
  }
});
```

#### `getAccountToken`

- 说明：游戏 H5 页面获取当前登录令牌，在服务器端处理。
- 参数：无。
- 返回：`Result`，`data.accountToken` 为登录令牌。

```js
QSMH5APP.getAccountToken({}, function (result) {
  if (result.code === 0) {
    var token = result.data.accountToken;
  }
});
```

#### `logout`

- 说明：H5 退出接口。原生清除 `accountToken`，关闭游戏页面、运营弹窗页面，隐藏悬浮球，并重新唤起 H5 登录页。
- 参数：无。
- 返回：`Result`。

```js
QSMH5APP.logout({}, function (result) {
  // 原生已清理会话并回到登录页
});
```

### 3.3 游戏信息

#### `getGameInfo`

- 说明：获取游戏相关信息，`packageCode` 为从安装包读取的渠道码；未写入、读取失败或格式无效时返回空字符串，不影响其他流程。
- 参数：无。
- 返回：`Result`，`data` 字段如下：
  - `packageCode`：渠道码。
  - `gameId`：游戏 ID。
  - `gameName`：游戏名称（可选）。
  - `versionName`：游戏版本号（可选）。

```js
QSMH5APP.getGameInfo({}, function (result) {
  if (result.code === 0) {
    var packageCode = result.data.packageCode;
  }
});
```

### 3.4 悬浮球

#### `setFloatingBall`

- 说明：H5 页面设置悬浮球状态。初始化接口给出悬浮球配置；`active=true` 指定其中一种样式（具体样式枚举后续完善）；`show` 控制悬浮球显示/隐藏。原生仅在状态发生变化时才重新加载悬浮球，状态一致时不重复加载，避免闪动。
- 参数：
  - `active`：布尔值，必填。`true` 启用其中一种指定样式，`false` 启用另一种；具体含义与初始化配置对应。
  - `show`：布尔值，必填。`true` 显示悬浮球，`false` 隐藏。
- 返回：`Result`。

```js
QSMH5APP.setFloatingBall({ active: false, show: false }, function (result) {
  // result.code === 0 表示设置成功
});
```

### 3.5 小窗口

#### `openMiniWindow`

- 说明：打开指定 H5 页面窗口。初始化配置中会列出多个 H5 页面，每个页面有窗口实例名称（如 `web_login`、`web_agreement`、`web_welfare`），本接口按实例名称打开对应窗口，并在最顶层显示。
- 参数：
  - `page`：窗口实例名称，必填，例如 `web_welfare`。
- 返回：`Result`。

```js
QSMH5APP.openMiniWindow({ page: "web_welfare" }, function (result) {
  // result.code === 0 表示打开成功
});
```

#### `closeMiniWindow`

- 说明：关闭指定实例的窗口。
- 参数：
  - `page`：窗口实例名称，必填，例如 `web_welfare`。
- 返回：`Result`。

```js
QSMH5APP.closeMiniWindow({ page: "web_welfare" }, function (result) {
  // result.code === 0 表示关闭成功
});
```

### 3.6 页面

#### `closePage`

- 说明：关闭当前窗口。
- 参数：无。
- 返回：`Result`。

```js
QSMH5APP.closePage(function (result) {
  // result.code === 0 表示关闭成功
});
```

## 4. 原生事件

### 4.1 事件监听

```js
QSMH5APP.on(eventName, handler);
QSMH5APP.off(eventName, handler);
```

### 4.2 事件清单

当前需求中，原生主动通知 H5 的场景主要通过异步回调完成，暂无强制注册的全局事件。以下为预留事件，待确认后启用：

| 事件名 | 触发时机 | 参数 |
| --- | --- | --- |
| `onAccountTokenChanged` | accountToken 变化 | `{ accountToken }` |
| `onPageVisibleChanged` | 页面显隐变化 | `{ visible }` |

## 5. 返回码定义

| code | 含义 |
| --- | --- |
| 0 | 成功 |
| 1001 | 参数错误 |
| 2001 | 未登录或 token 缺失 |
| 2002 | token 无效或已过期 |
| 3001 | 一键登录唤起失败 |
| 3002 | 用户取消一键登录 |
| 4001 | 渠道码读取失败 |
| 5001 | 悬浮球设置失败 |
| 9999 | 未知错误 |

## 6. 待确认问题

- 命名空间已定为 `QSMH5APP`。
- 方法名、参数名与返回码是否定稿。
- 初始化配置中 H5 页面窗口实例的字段结构（含尺寸与定位）。
- 悬浮球 `active` 的样式枚举结构。
- 事件清单是否需要补充页面生命周期类事件。