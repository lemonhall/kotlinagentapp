# v4 Plan：Web Tab（持久 WebView 容器）

## Goal

新增 `Web` 页签，提供一个在页签切换过程中不销毁的 WebView 容器，作为后续 Agent 自动化与预览截图的“真实载体”。

## PRD Trace

- REQ-0001-050

## Scope

### In

- BottomNavigation 增加第 4 个入口：`Web`
- Activity 级别创建并持有一个 WebView（单实例）
- Web 页签提供简单控制按钮：Back / Forward / Reload（能用即可）

### Out

- 不做多 WebView/多窗口/多标签页
- 不做登录态持久化策略设计（让系统 WebView 自己处理 cookies 即可）
- 不做复杂地址栏、书签、下载管理

## Acceptance（可验证）

1) BottomNavigation 出现 `Web` 页签，点开可看到 WebView
2) 在 Web 页打开某个 URL 后，切到 Chat 再切回 Web：仍是原页面（history 未清空）

## Files

- `app/src/main/res/menu/bottom_nav_menu.xml`
- `app/src/main/res/navigation/mobile_navigation.xml`
- `app/src/main/java/com/lsl/kotlin_agent_app/MainActivity.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/java/com/lsl/kotlin_agent_app/ui/web/WebFragment.kt`（新增）
- `app/src/main/res/layout/fragment_web.xml`（新增）
- `app/src/main/res/values/strings.xml`（新增/更新）

## Steps（Strict）

1. 实现 Web 页签与导航入口（menu + navGraph + strings）
2. 在 Activity 中新增一个“持久容器”并创建 WebView（默认隐藏/不可交互）
3. WebFragment 负责：显示/隐藏该容器 + 绑定控制按钮
4. 手动冒烟：Web 打开网址后来回切页签验证不重置
5. 验证命令：
   - `.\gradlew.bat :app:assembleDebug`
   - `.\gradlew.bat :app:testDebugUnitTest`
   - `.\gradlew.bat :app:installDebug`

## Risks

- WebView 生命周期与 Activity/Fragment 切换易出坑（崩溃、空白、丢历史）
  - 缓解：Activity 单例持有 + 仅切换可见性；核心操作全部主线程

