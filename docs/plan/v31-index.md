# v31 Index：Files 播放器体验增强（封面/播放模式/歌词/音量）

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0029-music-player.md`
- ECN：`docs/ecn/ECN-0004-music-player-ux-upgrade.md`
- 本轮目标（v31）：在不扩大“仅 `musics/` 子树启用播放器能力”的边界前提下，补齐播放器体验闭环：封面渲染、4 种播放模式 + 自动续播、歌词展示（含时序高亮 best-effort）、音量控制（mute + slider + 持久化），并提供稳定的单测证据。

## Milestones

### M1：封面 + 播放模式 + 自动下一曲 + 音量

- PRD Trace：
  - PRD-0029：REQ-0029-060 / REQ-0029-061 / REQ-0029-062 / REQ-0029-065 / REQ-0029-066
- DoD（硬口径）：
  - mini bar 至少展示封面缩略图；无封面时展示占位图（不得崩溃）；
  - 支持 4 种播放模式（随机循环/顺序循环/单曲循环/播放一次），UI 可切换且状态可见；
  - 曲目自然结束时按模式自动续播（除播放一次外不得停止）；
  - UI 提供 next/prev；队列 size=1 时不崩溃且行为可解释；
  - 音量提供 mute + slider，并在下次打开仍保持；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（至少覆盖播放模式状态机）。

### M2：歌词展示（USLT + LRC 时间戳高亮 best-effort）

- PRD Trace：
  - PRD-0029：REQ-0029-063 / REQ-0029-064
- DoD（硬口径）：
  - 若 mp3 含 ID3 `USLT`，播放器 UI 可查看歌词文本；
  - 若歌词文本包含 LRC 时间戳（如 `[01:23.45]`），随播放位置高亮当前行（best-effort，不因异常歌词崩溃）；
  - （可选）同名 `.lrc` sidecar 支持在 v31 计划中锁定策略并实现；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖歌词解析）。

## Plan Index

- `docs/plan/v31-music-player-ux.md`

## ECN Index

- ECN-0004：`docs/ecn/ECN-0004-music-player-ux-upgrade.md`

## Review（Evidence）

- Unit tests：`.\gradlew.bat :app:testDebugUnitTest` ✅（2026-02-18）
