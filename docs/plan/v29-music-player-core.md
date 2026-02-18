# v29 Plan：Files 内置 MP3 播放器（metadata + mini play bar）

## Goal

按 PRD-0029 的 v29 范围交付一个“只支持 MP3”的播放器最小闭环：Files 根目录约定 `musics/`（严格小写；仅该子树启用音乐视图与 metadata）、Files 可点播、mini play bar、页签切换不影响播放；并确保播放器运行时与 UI 解耦，未来可被 v30 的 `terminal_exec music` 控制面复用。

补充边界（已确认）：v29 需要 **退到后台/锁屏也继续播放**（因此必须采用前台服务 + 媒体通知 + MediaSession）。

## PRD Trace

- PRD-0029：REQ-0029-000 / REQ-0029-001 / REQ-0029-002 / REQ-0029-002A / REQ-0029-003
- PRD-0029：REQ-0029-010 / REQ-0029-011 / REQ-0029-012
- PRD-0029：REQ-0029-020 / REQ-0029-020A / REQ-0029-021 / REQ-0029-022 / REQ-0029-023 / REQ-0029-024 / REQ-0029-025 / REQ-0029-026 / REQ-0029-027
- PRD-0029：REQ-0029-030 / REQ-0029-031

## Scope

做：

- 播放器运行时（独立于 Files Fragment）：
  - controller/service + 可观察的 now playing state
  - AudioFocus 基本处理（至少能正确暂停/恢复）
  - 前台服务（Foreground Service）+ 媒体通知 + MediaSession（保证后台/锁屏继续播放）
- Files（Dashboard）页签：
  - 根目录特殊目录 `musics/`（严格小写；不存在则创建；并在列表中清晰标识用途）
  - 仅在 `musics/` 子树：`.mp3` 条目识别与点播入口 + metadata 摘要展示（best-effort + fallback）
  - `musics/` 子树外：不读取 mp3 metadata，不改变 Files 既有 mp3 行为
  - `musics/` 视图提供“后台播放排障”入口（静态说明即可；覆盖华为 Nova 9 常见后台限制设置）
  - mini play bar（已确认：只在 Files 页签可见）
- 单元测试（Robolectric）：
  - metadata 解析 best-effort + fallback
  - “Fragment 销毁/切换页签”不影响播放器状态（通过 controller/service 测试证明）
  - （最小）前台服务/通知权限缺失时不崩溃的降级行为（至少能给出可解释状态）

不做（v29）：

- 其他格式、在线播放、歌词、均衡器、完整音乐库扫描
- metadata 写入
- `terminal_exec music` 命令（留到 v30）

## Acceptance（硬口径）

见 `docs/prd/PRD-0029-music-player.md` 的 v29 Acceptance。

## Files（规划）

> 以现有目录风格为准，核心原则是“播放器运行时与 UI 解耦，便于 v30 CLI 复用”。

- Files UI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardFragment.kt`
  - `app/src/main/res/layout/fragment_dashboard.xml`（若 mini bar 放在 Files）
  - `app/src/main/java/com/lsl/kotlin_agent_app/MainActivity.kt`（若 mini bar 做成全局浮条）
- 播放器运行时（新建）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/*`（建议新包：player/controller/state/metadata）
  - `app/src/main/AndroidManifest.xml`（若引入 Service）
- 依赖：
  - `app/build.gradle.kts`（如引入 Media3）
  - `gradle/libs.versions.toml`（如做 version catalog 扩展）
- 单测：
  - `app/src/test/java/com/lsl/kotlin_agent_app/*`（新增 media 相关测试）

## Steps（Strict / TDD）

1) Analysis：播放边界已锁定（跨页签 + 后台/锁屏持续播放）；mini bar 已锁定（只在 Files 页签可见）。  
2) TDD Red：先写 metadata 读取与 fallback 测试；再写 controller/service 的状态机测试（play/pause/stop/seek/track switch）。  
3) TDD Green：实现 metadata reader + controller/service（先用 fake player 让单测可跑，再接真播放器）。  
4) UI：Files 根目录 `musics/` 特殊目录 + 仅该子树 `.mp3` 点播/metadata + mini play bar 绑定状态流。  
5) Refactor：把 UI 与运行时边界写清（依赖注入/单例持有点固定，避免散落）。  
6) Device QA：在华为 Nova 9 上做最小验收（锁屏 5 分钟/后台 5 分钟不断播 + 通知可控播），并把结果写入 v29 回顾区（失败必须留痕）。  
7) Verify：`.\gradlew.bat :app:testDebugUnitTest`。  

## Risks

- Robolectric 对真实音频播放支持有限 → 用可注入 player adapter + fake 来证明状态机；必要时补 instrumentation test 做最短真机回归。  
- AudioFocus 行为差异 → 先实现“最小正确暂停/恢复”，并把策略写入测试与文档（避免体验漂移）。  
- 后台/锁屏限制与通知权限：Android 13+ 通知权限、不同 ROM 的电量优化可能导致后台播放被系统终止 → 需要可解释提示，并在必要时引导用户处理权限/电量优化。  
