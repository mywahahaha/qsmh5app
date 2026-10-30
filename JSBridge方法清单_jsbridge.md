# JS Bridge 方法清单

## 文档信息

- 项目：qishimeng_h5_sdk
- 文档版本：v0.14
- 更新日期：2026-10-29
- 状态：业务规则已对齐需求文档；Bridge 签名仍需 Android/H5 联调确认

## 1. 概述与通用约定

业务流程、初始化配置与字段口径以[安卓 H5 游戏包开发需求文档](安卓H5游戏包开发需求文档_requirements.md)为准；本文展开 H5 与原生之间的具体方法签名。初始化接口地址由 SDK 硬编码为 `https://box.74meng.com/h5_app/init`。方法名、参数和返回码为联调建议稿，需 Android 与 H5 双方确认。

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

- 当前初始化配置示例中的协议、登录、福利大厅和游戏页面均使用 `box.74meng.com`；允许跳转域名由初始化响应 `jumpDomain` 提供，示例包含 `ipay.74meng.com` 和 `box.74meng.com`。
- H5 页面通过 `QSMH5APP` 与原生通讯；`jumpDomain` 仅用于游戏内跳转外部浏览器，允许 `http`、`https` 并精确匹配主机名，不用于 Bridge 来源校验。Bridge 来源信任须由独立策略控制。
- 原生按用途拆分并命名 WebView 实例：`web_login`、`web_agreement`、`web_welfare`、`web_game`。
- 页面 URL、实例名与窗口尺寸由初始化响应的 `h5Pages` 提供；具体字段和示例见需求文档第 4.1 节。

## 2. 方法清单总表

| 方法名 | 方向 | 分类 | 说明 |
| --- | --- | --- | --- |
| `agreePrivacyPolicy` | H5 → 原生 | 隐私协议 | 同意隐私协议 |
| `rejectPrivacyPolicy` | H5 → 原生 | 隐私协议 | 清除本地同意状态并退出应用 |
| `requestPhoneLogin` | H5 → 原生 | 登录 | 唤起手机一键登录 |
| `notifyLoginSuccess` | H5 → 原生 | 登录 | H5 登录成功通知，回传 accountToken |
| `getAccountToken` | H5 → 原生 | 登录 | 获取本地缓存的 accountToken |
| `logout` | H5 → 原生 | 登录 | 退出登录，清理会话 |
| `getGameInfo` | H5 → 原生 | 游戏信息 | 获取游戏相关信息 |
| `saveGameRole` | H5 → 原生 | 游戏角色 | 按游戏 ID 全量替换角色信息 |
| `getGameRole` | H5 → 原生 | 游戏角色 | 按游戏 ID 获取本地角色信息 |
| `saveLocalStorage` | H5 → 原生 | 本地存储 | 以键值对象或键值对象数组保存数据，同名 key 覆盖 |
| `getLocalStorage` | H5 → 原生 | 本地存储 | 按 key 读取字符串，不存在时为空字符串 |
| `isAppInstalled` | H5 → 原生 | 第三方应用 | 按包名异步检测应用是否安装 |
| `openApp` | H5 → 原生 | 第三方应用 | 按包名打开应用的系统启动入口 |
| `setFloatingBall` | H5 → 原生 | 悬浮球 | 设置悬浮球状态（样式与显隐） |
| `openMiniWindow` | H5 → 原生 | 小窗口 | 打开指定 H5 页面窗口（按实例名） |
| `closeMiniWindow` | H5 → 原生 | 小窗口 | 关闭指定实例窗口 |
| `changeGameUrl` | H5 → 原生 | 游戏页面 | 更换 `web_game` 地址并置顶 |
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

- 说明：用户点击“拒绝”时调用，原生清除本地隐私同意状态并退出应用，不弹额外提示；下次启动重新按 `privacyAuto` 处理。
- 参数：无。
- 返回：无（原生清除状态后退出应用）。

```js
QSMH5APP.rejectPrivacyPolicy();
```

### 3.2 登录

#### `requestPhoneLogin`

- 说明：此方法仅负责唤起阿里云授权弹窗。仅当初始化配置 `aliyunOnekeyConfig.open=1` 且已同意启动隐私协议时可启动，H5 无需传入 SDK 密钥。SDK 确认弹窗展示成功后立即回调成功，成功回调送达后原生关闭 `web_login`。
- 参数：`scene` 可选，默认 `"login"`；当前不改变认证策略。
- 返回（同次请求只回调一次）：
  - 展示成功：`{code:0,msg:"唤起成功",data:true}`；不返回 `accountToken`，不表示游戏登录完成。
  - 展示前失败（含已有认证会话时重复唤起）：`{code:3001,msg:"一键登录唤起失败",data:null}`；保留当前 H5 登录窗口，不打断正在进行的原生认证。
  - 功能关闭：`{code:3003,msg:"一键登录未开启",data:null}`。
- 展示后的关闭、其他登录方式、取号及兑换结果，不在本方法范围内，不再回调原请求。原生独立处理：取消或失败恢复新的 H5 登录窗口；兑换成功安全保存业务 Token 并打开 `web_game`。

```js
QSMH5APP.requestPhoneLogin({ scene: "login" }, function (result) {
  if (result.code === 0) {
    // 授权弹窗已展示；此回调结束后原生关闭 H5 登录窗口。
  } else {
    // 唤起失败，当前登录页继续显示，可展示 result.msg。
  }
});
```

#### `notifyLoginSuccess`

- 说明：H5 登录页自行处理登录逻辑；登录成功后调用，将 `accountToken` 交给原生安全保存，打开游戏 WebView 并销毁登录实例 `web_login`。H5 登录页在本地 Token 自动登录和阿里云登录均未成功或一键登录关闭时进入。
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

- 说明：H5 退出接口。原生清除持久化的 `accountToken`，销毁游戏、福利大厅和运营弹窗等当前会话页面，隐藏悬浮球，然后从一键登录步骤重新开始：初始化 `aliyunOnekeyConfig.open=1` 时尝试阿里云授权；未开启、配置不足、用户取消或认证/兑换失败时，创建新的 H5 登录实例 `web_login`。不再尝试旧 Token 自动登录，不重跑初始化和更新检查。任一方式重新登录成功后保存新 Token，并创建新的游戏实例。不会清除 `saveLocalStorage` 保存的数据或隐私同意状态。
- 旧页面从容器关闭后，其后续 Bridge 请求立即失效，淡出期间的旧回调不能重新打开游戏或修改新会话。
- 参数：无。
- 返回：`Result`，`{code:0,msg:"ok",data:true}` 表示已受理退出并启动后续流程，不代表一键登录已完成。

```js
QSMH5APP.logout({}, function (result) {
  // 后续由原生关闭旧页面，并按配置进入一键登录或 H5 登录；不表示重新登录成功。
});
```

### 3.3 游戏信息

#### `getGameInfo`

- 说明：获取 APP 本地保存的游戏信息。`deviceCode` 只有用户同意隐私协议后才读取/生成并返回；未同意时返回空字符串。其余字段按各自来源返回。
- 参数：无。
- 返回：`Result`，`data` 字段如下：
  - `packageCode`：渠道码；未获取时为空字符串。
  - `gameId`：游戏出包时指定、APP 本地保存的整数游戏 ID。
  - `gameVersion`：游戏出包时指定、APP 本地保存的版本号。
  - `accountToken`：登录成功后安全存储的登录凭证；未登录时为空字符串。
  - `deviceType`：客户端类型，本期固定为 `android`。
  - `deviceCode`：设备码；未同意隐私协议时为空字符串。

```js
QSMH5APP.getGameInfo({}, function (result) {
  if (result.code === 0) {
    var gameInfo = result.data;
  }
});
```

#### `saveGameRole`

- 说明：以 `gameId` 为唯一索引保存游戏角色，每个游戏仅保留一份角色资料。同一 `gameId` 再次提交时全量替换旧数据，即使 `roleId` 不同也会覆盖；未提交的可选字段不保留旧值，未知字段或类型不匹配字段丢弃。其他 `gameId` 的数据保留。
- 参数：`gameId`（整数）和 `roleId`（非空字符串）必填；其余字段可选：`roleName`、`serverId`、`serverName`、`account`（字符串），`roleLevel`、`rolePower`（整数）。
- 返回：`Result`，`data` 为布尔值。必要参数缺失/无效或写入失败时为 `false`；成功保存或替换时为 `true`。
- 清理时机：`web_game` 的 H5 主文档每次加载成功后，清空本地所有游戏的角色信息，包括首次加载、刷新和 `changeGameUrl` 重新加载。仅接受导航或加载失败时不清空；登录页、福利页等其他页面加载不清空角色。角色需要由 H5 在本次加载成功后重新保存。

```js
QSMH5APP.saveGameRole({
  gameId: 266,
  roleId: "role-001",
  roleName: "角色名",
  serverId: "server-01",
  serverName: "一区",
  account: "player",
  roleLevel: 10,
  rolePower: 1000
}, function (result) {
  var saved = result.code === 0 && result.data === true;
});
```

#### `getGameRole`

- 说明：按 `gameId` 读取该游戏本地保存的角色信息。
- 参数：`{ gameId: 266 }`，`gameId` 为整数，必填，无需传入 `roleId`。
- 返回：`Result`；存在时 `data` 为角色信息对象，无对应数据、索引无效或读取失败时 `data` 为 `false`。角色对象仅包含实际存储的有效字段。游戏页面加载成功会清空所有游戏角色，重新保存前读取返回 `false`。

```js
QSMH5APP.getGameRole({ gameId: 266 }, function (result) {
  if (result.code === 0 && result.data !== false) {
    var role = result.data;
  }
});
```

### 3.4 悬浮球

#### `setFloatingBall`

- 说明：H5 页面设置悬浮球运行状态。悬浮球在用户登录完成且 `web_game` 实例加载后创建；福利大厅 `web_welfare` 显示时强制临时隐藏，关闭后恢复运行状态；游戏页未加载或已关闭时不显示。初始化配置 `floatball.show=false` 时初始不显示，后续本方法可更新运行时显隐。
- 参数：
  - `show`：布尔值，必填。`true` 显示悬浮球，`false` 隐藏。
  - `active`：布尔值，必填。`true` 使用初始化配置的 `floatball.image_active`；`false` 使用 `floatball.image_default`。默认值为 `false`。
- 返回：`Result`。

原生按图标状态变化更新图像：如果悬浮球已存在且目标图标与当前图标相同，不重新加载图标，避免闪动。只改变显隐时也不重载图标。宽高和位置由初始化 `floatball` 配置提供；H5 调用此方法不改变尺寸或位置。

```js
QSMH5APP.setFloatingBall({ show: true, active: false }, function (result) {
  // result.code === 0 表示设置成功
});
```

### 3.5 小窗口

#### `openMiniWindow`

- 说明：打开指定 H5 页面窗口。初始化配置中会列出多个 H5 页面，每个页面有窗口实例名称（如 `web_login`、`web_agreement`、`web_welfare`），本接口按实例名称打开对应窗口，并在最顶层显示。同名实例已存在时直接置顶。游戏中打开福利大厅 `web_welfare` 时保留游戏实例和当前文档状态，不关闭或重建游戏。
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

#### `changeGameUrl`

- 说明：将游戏实例 `web_game` 导航到指定 URL，并切换到最前端。URL 必须是有效的绝对 HTTP/HTTPS 地址，且主机名与初始化配置 `h5Pages.game.url` 的主机名精确匹配；不自动允许子域，也不使用 `jumpDomain` 校验。若 `web_game` 已存在，则在该实例中导航到新 URL 并置顶；若不存在，则创建实例、加载该 URL 并置顶。
- 大厅调用时：直接使用现有游戏 WebView 加载新地址，不关闭、销毁或重新创建游戏实例；福利大厅保留在游戏后方。新地址会加载新文档，旧页面的 JS 状态随导航结束；游戏置顶后恢复运行时悬浮球状态，再打开大厅时复用原大厅实例。
- 参数：`url`（非空字符串，必填）。
- 返回：`Result`，`data` 为布尔值。地址校验通过且原生已接受导航/创建请求时为 `true`；参数、地址校验或原生导航操作失败时为 `false`。`true` 仅表示导航已被接受，不代表远端页面已加载成功。

```js
QSMH5APP.changeGameUrl({ url: "https://box.74meng.com/h5?gid=266" }, function (result) {
  if (result.code === 0 && result.data === true) {
    // 原生已接受导航并将 web_game 置顶
  }
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

### 3.7 本地存储

这两个方法允许所有由 SDK 注入 Bridge 的可信 H5 页面调用，包括协议页、登录页、福利页、游戏页等；不要求已登录或已同意隐私协议。各可信页面共享同一份应用内存储，不按域名、WebView 实例或账号隔离，Bridge 来源信任仍沿用 SDK 既有规则。

数据保存于应用私有本地持久化存储，与浏览器 `window.localStorage` 独立；应用重启后仍保留，退出登录不会清除，卸载应用后由 Android 清除。

#### `saveLocalStorage`

- 说明：支持单一设置和批量设置。单一设置传入 `{key, value}` 对象；批量设置传入这类对象组成的数组。每一项按 `key` 写入 `value`；同名 key 覆盖旧值，同一批次重复 key 以最后一项为准，未传入的 key 保留原值。空数组不修改数据。
- 参数：单一设置如 `{key:"abc", value:"123"}`；批量设置如 `[{key:"abc",value:"123"},{key:"def",value:"456"},{key:"xxx",value:"000"}]`。每项 `key` 必须为字符串，支持空字符串 key；缺失或类型错误、数组元素不是键值对象时，返回 `code:1001`，整批不写入。不再使用旧版 `{abc:123}` 参数结构。
- 值转换：`value` 为字符串时原样保存；数字按字符串保存（例如 `123` 保存为 `"123"`）；布尔值、对象、数组、`null`、`undefined` 等非字符串/数字值均按空字符串 `""` 保存，不删除 key；未传 `value` 时也保存为空字符串。
- 返回：`Result`，`data` 为布尔值；持久化成功为 `true`，写入失败为 `false`，异常沿用通用错误码。

```js
// 单一设置
QSMH5APP.saveLocalStorage({ key: "abc", value: "123" }, function (result) {
  if (result.code === 0 && result.data === true) {
    // abc 为 "123"
  }
});

// 批量设置
QSMH5APP.saveLocalStorage([
  { key: "abc", value: "123" },
  { key: "def", value: "456" },
  { key: "xxx", value: "000" }
], function (result) {
  // 成功后可用 getLocalStorage({key:"abc"}) 等读取各项
});
```

#### `getLocalStorage`

- 说明：按 key 获取本地保存的值。
- 参数：`{key: String}`，`key` 必填且必须为字符串；支持空字符串 key。缺失或类型错误返回 `code:1001`。
- 返回：`Result`，`data` 固定为字符串，直接为保存的值，不额外包装对象。key 不存在时返回 `code:0, data:""`；参数错误或读取异常时 `data` 也为空字符串，具体错误由 `code`、`msg` 判断。

```js
QSMH5APP.getLocalStorage({ key: "abc" }, function (result) {
  if (result.code === 0) {
    console.log(result.data); // "123"
  }
});

QSMH5APP.getLocalStorage({ key: "missing" }, function (result) {
  console.log(result.data); // ""
});
```

### 3.8 第三方应用（Android）

这两个接口仅适用于已注入 `QSMH5APP` 的安卓 SDK WebView，纯浏览器页面不能检测手机应用。安装状态限定当前用户/资料空间；不是其他用户或工作资料空间的全设备扫描。

#### `isAppInstalled`

- 调用：`QSMH5APP.isAppInstalled({packageName: "com.me74.qsmhz"}, callback)`。
- 参数：`packageName` 必填，必须为非空、符合安卓包名格式的字符串；不接受 URI、组件名或带空格字符串。
- 回调：可见且已安装为 `{code:0,msg:"ok",data:true}`；未安装或受系统包可见性限制时为 `{code:0,msg:"ok",data:false}`。
- 参数错误为 `code:1001,data:false`；非预期查询异常为 `code:9999,data:false`。先检查 `code`，不要将操作异常当成未安装。

```js
QSMH5APP.isAppInstalled({ packageName: "com.me74.qsmhz" }, function (result) {
  if (result.code !== 0) {
    console.error(result.msg);
    return;
  }
  console.log(result.data); // 布尔值 true / false
});
```

#### `openApp`

- 调用：`QSMH5APP.openApp({packageName: "com.me74.qsmhz"}, callback)`；包名约束与检测接口一致。
- 返回：系统接受启动请求为 `{code:0,msg:"ok",data:true}`，不代表第三方页面已经展示或业务已完成；未安装、系统无法找到入口或拒绝启动为 `{code:0,msg:"ok",data:false}`。参数错误为 `code:1001,data:false`；非预期异常为 `code:9999,data:false`。
- 应由 H5 用户点击触发，不在加载时自动打开；原生接口本身不能验证 WebView 的用户手势。不会下载应用、跳转商店或接受自定义 Intent/组件/URI。应用切后台后，H5 回调可能在回到页面时才可观察到。

```html
<button id="open-game" type="button">打开游戏</button>
<script>
document.getElementById("open-game").addEventListener("click", function () {
  QSMH5APP.openApp({ packageName: "com.me74.qsmhz" }, function (result) {
    if (result.code !== 0) console.error(result.msg);
    else if (!result.data) console.log("无法打开：未安装、无启动入口或被系统限制");
  });
});
</script>
```

#### Android 11+ 可见性与接入注意事项

- 当前 Manifest 已声明 `com.me74.qsmhz` 和 MAIN/LAUNCHER 查询，覆盖该业务包及匹配桌面启动入口的普通游戏应用，不申请 `QUERY_ALL_PACKAGES`。
- **不能保证检测任意包。** 其他无桌面入口的目标包，需在 `android/app/src/main/AndroidManifest.xml` 的 `<queries>` 中添加 `<package android:name="目标包名" />`，然后重新构建 APK。H5 传参不能动态扩大系统包可见性；不可见的已安装包也可能返回 `false`。
- 新增接口必须重新构建并安装安卓包，仅更新 H5 或 `qsm-jsbridge.js` 不会让旧 APK 获得原生能力。
- 仅在业务需要时查询指定包，按分发平台政策最小化查询范围，并在隐私政策中披露用途，不进行全量应用枚举。
- 示例脚本另提供 `GameBridge.isAppInstalled(packageName)`、`GameBridge.openApp(packageName)`，Promise 成功直接得到布尔值，非零 `code` 则 reject。完整按钮示例见 `示例/vue2-jsbridge-demo.html`。

## 4. 原生事件

### 4.1 事件监听

`QSMH5APP.on/off` 用于“原生主动通知 H5”，与前面“H5 主动调用原生”的方法互补。

- 主动调用：H5 发起，回调只返回一次。
- 事件监听：H5 注册，原生可多次、异步推送，直到 `off` 取消。

```js
// 注册
function onAccountTokenChanged(data) {
  if (!data.accountToken) {
    // token 已失效，做登出或跳登录处理
  }
}
QSMH5APP.on("onAccountTokenChanged", onAccountTokenChanged);

// 取消，页面销毁时调用，避免重复回调
QSMH5APP.off("onAccountTokenChanged", onAccountTokenChanged);
```

适用场景：原生先发生变化、需要反向推给 H5 的情况，例如 token 过期/刷新、页面切后台或前台、窗口被覆盖等。后续可扩展悬浮球状态变化、更新下载进度等。

注意事项：
- 事件名需在桥接层统一约定并做参数校验。
- 页面销毁、窗口关闭时必须调用 `off` 解绑，防止重复注册造成回调叠加或内存泄漏。

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
| 3003 | 一键登录未开启 |
| 4001 | 渠道码读取失败 |
| 5001 | 悬浮球设置失败 |
| 5002 | 游戏角色数据读写失败 |
| 9999 | 未知错误 |

## 6. 待确认问题

- Android/H5 联调确认最终方法签名、参数字段、返回码和回调时机。
- 确认原生主动事件清单是否需要页面生命周期类事件。
