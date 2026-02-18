# v29 Index：Files 内置 MP3 播放器（metadata + mini play bar）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0029-music-player.md`
- 本轮目标（v29）：交付“只支持 MP3 的播放器最小闭环”，满足：Files 根目录约定 `musics/`（严格小写；仅该子树启用音乐视图与 metadata）、Files 可点播、mini play bar、页签切换不影响播放、**后台/锁屏也继续播放**；并把“播放器运行时与 UI 解耦”固化成验收与单测。

## Milestones

### M1：`musics/` 音乐视图 + mini play bar + 跨页签/后台/锁屏持续播放

- PRD Trace：
  - PRD-0029：REQ-0029-000 / REQ-0029-001 / REQ-0029-002 / REQ-0029-002A / REQ-0029-003
  - PRD-0029：REQ-0029-010 / REQ-0029-011 / REQ-0029-012
  - PRD-0029：REQ-0029-020 / REQ-0029-020A / REQ-0029-021 / REQ-0029-022 / REQ-0029-023 / REQ-0029-024 / REQ-0029-025 / REQ-0029-026 / REQ-0029-027
  - PRD-0029：REQ-0029-030 / REQ-0029-031
- DoD（硬口径）：
  - Files 根目录存在 `musics/`（严格小写；不存在则 App 自动创建）；
  - 仅 `musics/` 子树内：点击 `.mp3` 可播放；同目录切到另一首可切歌；
  - `musics/` 子树外：Files 不读取 mp3 metadata，且不改变既有 mp3 的打开/分享逻辑；
  - 切换到其他页签后仍持续播放（不因 Fragment 销毁停止）；
  - 退到后台或锁屏后仍持续播放（前台服务 + 媒体通知 + MediaSession）；
  - `musics/` 视图提供“后台播放排障”入口（覆盖华为 Nova 9 常见后台限制设置）；
  - 华为 Nova 9 最小手动验收：锁屏 5 分钟/后台 5 分钟不断播 + 通知可控播；
  - mini play bar **只在 Files 页签可见**，支持播放/暂停与进度显示；
  - 离开 Files 页签后：必须仍可通过媒体通知控制播放/暂停（至少播放/暂停）；
  - metadata best-effort：至少显示 title（或文件名）+ duration；有 ID3 时展示 artist/album；
  - 反作弊：播放器不允许绑在 Files Fragment；必须由 Service/Controller 承载；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0；
  - 提交与推送：`git status --porcelain=v1` 为空。

## Plan Index

- `docs/plan/v29-music-player-core.md`

## ECN Index

- （本轮无）

## Review（Evidence）

- Unit tests：`.\gradlew.bat :app:testDebugUnitTest` ✅（2026-02-18）
- Device QA（华为 Nova 9）：⏳ 待你手动验收并回填（锁屏 5 分钟/后台 5 分钟不断播 + 通知可控播）
