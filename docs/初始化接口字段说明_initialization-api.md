# 初始化接口字段说明

更新日期：2026-10-28。本文说明初始化接口的已确认字段，业务流程以 [安卓 H5 游戏包开发需求文档](../安卓H5游戏包开发需求文档_requirements.md) 为准。新契约已接入原生实现；具体测试结果及真机验收边界以联调指南为准。

## 请求与响应

- 方法：`POST`。
- 固定地址：`https://box.74meng.com/h5_app/init`，不可被初始化响应覆盖。
- 当前客户端请求体：`{}`。
- 固定 Header：`Gameid`、`Devicecode`、`Packagecode`、`Devicetype`、`Accountoken`、`Gameversion`。无值时仍发送空字符串；未同意隐私协议前 `Devicecode`、`Accountoken` 为空。
- `Gameversion` 来自构建时填写的游戏版本，渠道分包只写入 `Packagecode`，不会更改游戏版本。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | number | `0` 成功；非 `0` 停止启动 |
| `msg` | string | 非 `0` 时显示“游戏初始化失败：”加此文案；JSON Unicode 转义自动还原为中文，缺失或空白时才回退显示错误码 |
| `data` | object | 成功配置；失败响应不要求提供 |
| `data.bgImage` | string | 可选 H5 底层背景绝对地址，等比例铺满；区别于本地启动闪图 |
| `data.updateInfo` | object | 更新窗口及处理方式，详见下节 |
| `data.jumpDomain` | string[] | 游戏内跳转到外部浏览器的精确主机白名单；不用于更新地址校验或 Bridge 来源校验 |
| `data.aliyunOnekeyConfig` | object | 阿里云一键登录配置，`open=1` 开启；完整子字段、默认值、样式与 iOS 参考见[一键登录独立需求](阿里云一键登录独立需求_aliyun-onekey-login.md) |
| `data.popupInterval` | object | 运营心跳地址 `url` 及秒数间隔 `interval` |
| `data.floatball` | object | 悬浮球初始显隐、图片、尺寸与位置，详见需求文档第 5 节 |
| `data.privacyAuto` | boolean | 尚未同意隐私时是否先显示协议页面；`false` 跳过并本地标记已同意 |
| `data.h5Pages` | object | 页面配置，以 `agreement/login/welfare/game` 映射页面实例及布局 |

## 更新字段 `data.updateInfo`

```json
{
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
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `open` | number | `0` 跳过更新并忽略其余字段；`1` 显示原生更新窗口 |
| `width` | number / string | 相对可用窗口宽度的百分比，`"50"`、`50` 或 `"50%"` 表示 50%，不是 50px；大于 0、至多 100 |
| `height` | number / string | `"auto"` 按内容自适应；数字、数字字符串或带 `%` 字符串按可用窗口高度的百分比计算；大于 0、至多 100 |
| `popup` | number | `1` 通过外部浏览器打开 `url`；`0` 在应用内部下载 APK 并请求系统安装 |
| `force` | number | `1` 正常更新不可关闭且无下方关闭按钮；`0` 可关闭并继续启动 |
| `title` | string | 头图上的白色标题 |
| `time` | string | 更新时间，按服务端字符串展示 |
| `desc` | string | 面板中的红色更新说明 |
| `version` | string | 目标版本文案；不会覆盖本次运行的游戏版本 Header |
| `url` | string | 绝对 HTTP/HTTPS 地址；`popup=0` 必须返回 APK 文件，`popup=1` 可返回外部更新页面 |

### 窗口与下载安装

- 原生绘图，头部使用随包 `bg.png`，白色面板圆角按 Android 15dp 适配；底层黑色遮罩透明度 45%。尺寸相对实际可用窗口计算，内容过长时在面板内滚动并保留关闭控件空间。
- `title/time/desc/version` 均为字符串，允许空字符串，对应文案为空时隐藏；`open=1` 的 `url` 必须有效且非空。各开关均为数字 `0/1`，不使用布尔值或字符串开关。
- “立即更新”为绿色圆角按钮；`force=0` 时下方显示圆形关闭按钮。
- 点击遮罩不关闭窗口；`force=1` 拦截返回键。外部浏览器打开成功后，`force=0` 结束更新并继续登录，`force=1` 保留弹窗，返回游戏后可再次更新。
- 内部下载显示渐变进度条和百分比。下载完成后保留进度，并在下方显示同款“立即安装”按钮，同时自动启动系统安装；取消安装后可以再次点击。
- Android 8+ 需要本应用的未知来源安装授权；下载文件经 FileProvider 的 `content://` URI 和临时读权限交给安装器。
- 隐私流程结束后、登录前检查更新；配置、下载或安装处理异常时沿用原需求，提示后结束更新并继续登录。正常强制状态下仍不可主动关闭。
- 若安装包需携带渠道码，服务端 `url` 应指向相应渠道 APK；更新配置中的 `version` 不负责写入或修改渠道。
- 仅完整下载且通过 APK 包名校验的文件才能交给系统安装器；签名、版本兼容和安装确认由 Android 系统处理。下载中禁止重复发起，关闭非强制窗口或 Activity 销毁时取消下载；长度未知时显示不确定进度，完成后显示 100%。

### 旧字段迁移

| 旧字段 | 新字段 | 变化 |
| --- | --- | --- |
| `isUpdate` | `open` | 是否进入更新流程 |
| `forceUpdate` | `force` | 是否允许关闭正常更新窗口 |
| `downloadUrl` | `url` | 根据 `popup` 决定外部页面或内部 APK 下载 |
| `isPop` | `popup` | 新字段明确具有业务含义：`1` 外部浏览器，`0` 应用内下载 |
| 无 | `width/height/time` | 增加百分比尺寸、自适应高度及更新时间文案 |

客户端与服务端应一起切换至新契约，旧字段不再作为当前更新接口定义。
