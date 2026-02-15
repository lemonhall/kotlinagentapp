# v4 Plan：Chat 画中画 Web 预览（可开关）

## Goal

在 Chat 页提供一个“画中画”预览：定时显示 WebView 截图 + 当前 URL，让用户在聊天时能观察 Agent 驱动 WebView 的过程；并可在 Settings 中默认开关。

## PRD Trace

- REQ-0001-052

## Scope

### In

- Settings 增加开关（默认关闭），写入 SharedPreferences
- Chat 页右上角显示圆角矩形预览（仅展示，不可交互）
- 预览定时刷新（<=2s 一次，best-effort；失败不影响聊天）
- 点击预览 → 跳转 Web 页签

### Out

- 不做拖拽/缩放/多位置停靠
- 不做“预览点击交互转发到 WebView”

## Acceptance（可验证）

- Settings 能持久化该开关（重进页面仍保持）
- 开启后 Chat 显示预览；关闭后不显示
- 点击预览可跳到 Web 页签
- `.\gradlew.bat :app:installDebug` 真机冒烟通过

## Files

- `app/src/main/java/com/lsl/kotlin_agent_app/ui/settings/SettingsFragment.kt`
- `app/src/main/res/layout/fragment_settings.xml`
- `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/ChatScreen.kt`
- `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/ChatFragment.kt`
- `app/src/main/java/com/lsl/kotlin_agent_app/web/WebPreviewCoordinator.kt`（新增，管理截图节流/定时）

## Steps（Strict）

1. Settings 增加 switch，并落盘到 prefs
2. 实现 WebPreviewCoordinator（按开关启动/停止定时截图）
3. ChatScreen 渲染预览卡片（圆角、带 URL 文本）
4. 预览点击跳转到 Web tab（NavController 导航）
5. 真机验证 + `:app:testDebugUnitTest` + `:app:installDebug`

## Risks

- 截图频率过高导致卡顿/内存压力
  - 缓解：固定小尺寸截图 + 2s 刷新 + best-effort 失败忽略

