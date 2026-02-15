# v4 Index：Web Tab（持久 WebView）+ Agent WebView Tool + Chat 画中画预览

## Vision（引用）

- PRD：`docs/prd/PRD-0001-kotlin-agent-app.md`
- 本轮聚焦：把“WebView 自动化”从预留点变成最小可用闭环：**一个持久 WebView + 一个语义收敛的 `WebView` 工具 + Chat 画中画预览（可开关）**。

## Milestones

### M1：Web Tab（持久 WebView 容器）

- PRD Trace：REQ-0001-050
- DoD（硬口径）：
  - BottomNavigation 新增 `Web` 页签（第 4 项）
  - Web 页签展示 WebView，并提供返回/前进/刷新按钮（可用即可，不追求美观）
  - 切页签不销毁 WebView：`Web -> Chat -> Web` 后页面不重置（URL 与 history 仍在）
  - `.\gradlew.bat :app:assembleDebug` exit code=0
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
  - `.\gradlew.bat :app:installDebug` 安装到真机（按 AGENTS.md 约定）
- Plan：`docs/plan/v4-webview-container.md`

### M2：Agent Tool（单一 `WebView` 工具，多 action）

- PRD Trace：REQ-0001-051
- DoD（硬口径）：
  - App 内注册 `WebView` 工具，并加入 `allowedTools`
  - 支持 action：
    - `goto(url)`
    - `get_state()` → `{ url, title, canGoBack, canGoForward, loading }`
    - `get_dom(selector?, mode?)`
    - `run_script(script)`（返回字符串或 JSON 字符串）
    - `back()` / `forward()` / `reload()`
  - 所有 WebView 操作都在主线程执行（工具 run 允许在后台协程触发）
  - `:app:testDebugUnitTest` 覆盖：输入校验、action 分发、错误结构化输出
- Plan：`docs/plan/v4-webview-tool.md`

### M3：Chat 画中画 Web 预览（可开关）

- PRD Trace：REQ-0001-052
- DoD（硬口径）：
  - Settings 提供开关（默认关闭）：开启后 Chat 右上角显示圆角矩形预览（截图 + URL）
  - 预览刷新频率：<= 2s 一次（best-effort；失败不影响聊天）
  - 点击预览可跳转到 Web 页签
  - `.\gradlew.bat :app:assembleDebug` exit code=0
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
  - `.\gradlew.bat :app:installDebug` 安装到真机（按 AGENTS.md 约定）
- Plan：`docs/plan/v4-chat-web-preview.md`

## Plan Index

- `docs/plan/v4-webview-container.md`
- `docs/plan/v4-webview-tool.md`
- `docs/plan/v4-chat-web-preview.md`

## Traceability Matrix（v4）

| Req ID | v4 Plan | Tests / Commands | Evidence |
|---|---|---|---|
| REQ-0001-050 | v4-webview-container | `:app:assembleDebug` / `:app:installDebug` | local |
| REQ-0001-051 | v4-webview-tool | `:app:testDebugUnitTest` | local |
| REQ-0001-052 | v4-chat-web-preview | `:app:installDebug` | local |

## ECN Index

- none

## Evidence（本地）

- 2026-02-15：
  - `.\gradlew.bat :app:assembleDebug` ✅
  - `.\gradlew.bat :app:testDebugUnitTest` ✅
  - `.\gradlew.bat :app:installDebug` ✅
