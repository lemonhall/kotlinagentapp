# Android 能力清单：适合暴露为伪 CLI（`terminal_exec`）的系统 API

日期：2026-02-17

目的：把“手机系统能力”整理成可被 Agent 以 **命令协议**调用的能力集合候选（不等于全部都做）。面向后续 PRD/计划：哪些能力可以做成 `terminal_exec` 白名单命令、需要什么权限、风险在哪里、上架是否敏感。

> 说明：这是一份 **候选清单与约束**，不是实现承诺；具体落地以 PRD/DoD 为准。

---

## 1) 低风险（通常可默认启用或弱确认）

### 1.1 通知（发通知）

- 能力：在本机发一条通知；点击通知可 deep link 回 App 某页面/某 run/session。
- 典型命令面：
  - `notify post --title ... --body ... --channel ...`
- 备注：无需危险权限；注意通知渠道与用户关闭通知场景的降级。

### 1.2 本地定时（应用内任务）

- 能力：基于 WorkManager/AlarmManager 触发后续自动化（参考 PRD-0009）。
- 典型命令面：
  - `schedule create --at ... --run <automation_id>`
- 备注：精确闹钟涉及 `SCHEDULE_EXACT_ALARM`（更敏感；Android 14+ / targetSdk 34+ 默认不授予，通常需要用户在系统设置里手动开启），优先走“非精确”。

### 1.3 文件与分享（SAF）

- 能力：调用系统文件选择器/保存对话框；将文件复制入 `.agents/inbox` 或 `.agents/artifacts`。
- 典型命令面：
  - `filepicker open --mime ...`
  - `share send --text ... --to <package?>`（见微信部分）
- 备注：尽量不碰“全盘读写权限”，走 SAF/Photo Picker。

### 1.4 TTS（朗读）

- 能力：把文本朗读出来（可做“提醒播报/读报告摘要”）。
- 典型命令面：
  - `tts speak --text ... --lang zh-CN`

### 1.5 系统只读状态

- 能力：电量/充电状态、网络连接状态、存储空间等。
- 典型命令面：
  - `device status`
- 备注：只读，隐私风险较低。

### 1.6 拨号界面（不直接拨出）

- 能力：打开系统拨号界面并填入号码（不直接拨出）。
- 典型命令面：
  - `dial open --number <phone>`
- 备注：可用 `Intent.ACTION_DIAL`；相比直接拨打（`CALL_PHONE`）风险低很多。

### 1.7 快捷方式（ShortcutManager）

- 能力：创建/更新桌面快捷方式（例如“一键打开某个 automation/run”）。
- 典型命令面：
  - `shortcut create --id ... --label ... --deep-link ...`
- 备注：低风险但体验价值高；注意 Android 版本差异与用户桌面支持情况。

### 1.8 下载管理器（DownloadManager）

- 能力：使用系统级下载任务下载文件并落盘到工作区（注意路径受限/SAF）。
- 典型命令面：
  - `download create --url ... --to artifacts/...`
- 备注：需要明确的存储策略（优先落到 App 私有目录/`.agents`）。

### 1.9 壁纸（WallpaperManager，可选）

- 能力：设置壁纸（场景窄，但低风险）。
- 典型命令面：
  - `wallpaper set --from <path>`

---

## 2) 中风险（需要运行时权限；适合“显式授权 + 可审计 + 默认关”）

### 2.0 剪贴板（Clipboard）

- 能力：写剪贴板、读剪贴板。
- 典型命令面：
  - `clipboard set --text ...`
  - `clipboard get`
- 风险与限制：
  - **读剪贴板**在新版本 Android 上限制更严（例如前台限制、系统提示/Toast 等），建议默认禁用或仅在前台显式动作中允许；
  - 写剪贴板相对低风险，但也应有最小化审计（避免把敏感信息写入日志）。

### 2.1 日历（Calendar Provider）

- 能力：读取/创建/更新日程，设置提醒。
- 权限：`READ_CALENDAR` / `WRITE_CALENDAR`
- 典型命令面：
  - `calendar list --from ... --to ...`
  - `calendar create --title ... --start ... --end ... --remind-min 10`
- 风险：包含个人行程隐私；审计落盘时要避免写入完整描述（或做脱敏/可选）。

### 2.2 联系人（Contacts Provider）

- 权限：`READ_CONTACTS` / `WRITE_CONTACTS`
- 命令面：
  - `contacts search --q ...`
- 风险：隐私；不建议默认启用。

### 2.3 定位

- 权限：`ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`（后台更敏感）
- 命令面：
  - `location get`
- 风险：高隐私；必须强提示与明确用途。

### 2.4 相机/相册

- 权限：`CAMERA`；相册访问优先 Photo Picker（权限更轻）
- 命令面：
  - `camera capture --to artifacts/...`
  - `photo pick --to inbox/...`

### 2.5 麦克风（语音）

- 权限：`RECORD_AUDIO`
- 命令面：
  - `audio record --seconds 10 --to artifacts/...`

---

## 3) 高风险/强约束（上架与合规压力大；可做但必须克制）

### 3.1 短信（SMS）

- 能力：发送/读取/监听短信。
- 权限：
  - 发送：`SEND_SMS`（敏感）
  - 读取/接收：`READ_SMS` / `RECEIVE_SMS` / `WRITE_SMS`（更敏感）
- 备注：Google Play 对 SMS/通话记录权限通常有严格限制；很多场景需要“默认短信应用”资格或豁免。
- 命令面（如果做）：
  - `sms send --to ... --body ... --confirm`
  - `sms inbox list`（通常不建议做）

### 3.2 通知监听（读取其它 App 的通知）

- 能力：监听并解析其它 App 通知（例如验证码/物流/日程提醒）。
- 入口：NotificationListenerService（需用户在系统设置手动开启）
- 风险：极高隐私；审计/落盘策略必须非常保守（最好只存 hash/摘要）。

### 3.3 无障碍（自动点击/读控件树）

- 能力：自动化任意 App 的 UI（包括选择联系人、自动发消息等）。
- 风险：滥用风险极高、上架压力最大；应视为“最后手段”，并强制可见 UI 提示与用户可随时停止。

### 3.4 使用情况访问（App 使用统计）

- 能力：最近打开的 App、使用时长等。
- 权限：`PACKAGE_USAGE_STATS`（用户手动授权）
- 风险：隐私；适合做“个人数据分析”，不适合默认开。

### 3.5 屏幕捕获/录屏（MediaProjection）

- 能力：截图/录屏，用于 artifact 或调试回放。
- 风险：每次都要系统授权弹窗；保存内容可能含敏感信息。

---

## 4) 适合做“触发器/事件源”的候选

（与 PRD-0008/0009 的 Automation/Queue 衔接）

- 电量/充电状态变化
- 网络状态变化
- 日历将到期（查询 + 定时）
- 收到通知（通知监听）
- 地理围栏（定位）
- 蓝牙设备连接变化

---

## 5) 微信相关：能否用 Intent 拉起并“给某人发消息”？

### 5.1 可行（官方、稳定、合规压力相对小）

- **拉起微信并分享文本/图片/文件**：走系统 Share（`ACTION_SEND`），限制目标包名为 `com.tencent.mm`，让用户在微信界面里选择联系人/群。
- 结论：可以做到“发内容到微信”，但通常做不到“指定某个人并自动发送”。

### 5.2 通常不可行（或非常不稳定/不建议）

- **用 Intent 直接打开微信某个联系人会话并预填消息**：微信对外公开的 Intent/deep link 能力很有限；想“定向到某人”一般需要：
  - 微信开放 SDK（分享/小程序等）且仍需要用户交互；或
  - 依赖微信内部 Activity/私有 scheme（高脆弱、随版本变；也可能违反平台/应用策略）；或
  - 无障碍自动化（高风险，见 3.3）。

### 5.3 推荐策略（如果要做）

- v1：只做 `share wechat`（用户最后一步选联系人），不做“指定联系人自动发”。
- 如确有“指定联系人”需求：优先评估微信 SDK 能覆盖的最小范围；否则必须走无障碍，并把它当作“高风险能力”单独开关与强审计。

---

## 6) 高危操作确认：BiometricPrompt（不是“命令”，而是网关能力）

- 目标：把“二次确认”从每个命令里抽出来，成为统一能力。
- 建议：
  - 对 C 类能力、或 B 类里“可能造成损失/外发”的能力（例如真实发送短信/推送/分享），强制弹窗确认；
  - 可选使用 `BiometricPrompt` 作为确认方式之一（生物识别/设备凭据），提高安全性与可追溯性。

---

## 6) 伪 CLI 命令面建议（统一风格）

建议先按命名空间拆：

- `notify ...`
- `calendar ...`
- `contacts ...`
- `location ...`
- `sms ...`
- `share ...`
- `device ...`
- `clipboard ...`
- `download ...`
- `shortcut ...`

并为每个命令声明：

- `capabilities`：读隐私/写隐私/网络/高风险
- `resource_limits`：输入/输出/耗时上限
- `auditing`：哪些字段必须写、哪些必须脱敏或禁止落盘

### 6.1 追加建议命令面（例）

```
clipboard set/get
location get
location fence create/delete
device info
device screenshot
tts speak --text "..."
download create --url "..." --to "..."
app list            # 注意：QUERY_ALL_PACKAGES 在新版本 Android/上架场景受限
```

### 6.2 触发器命名建议（统一前缀）

```
trigger on-sms-received
trigger on-notification
trigger on-location-enter
trigger on-battery-low
trigger on-network-change
trigger on-bluetooth-connect
```

---

## 7) 架构建议：权限网关（Risk Gate）层

把“风险分级 + 授权 + 确认”集中到一层，避免每个命令各写一套：

```
用户/Agent 发出命令
  ↓
权限网关（检查风险等级 + 用户授权状态）
  ↓
A 类 → 直接执行
B 类 → 检查运行时权限；未授权则引导/请求
C 类 → 强制二次确认（弹窗/生物识别）+ 可随时停止
  ↓
执行 + 写审计日志（敏感字段打码/禁止落盘）
```

