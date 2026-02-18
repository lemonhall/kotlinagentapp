# v30 Index：MP3 播放器 CLI 控制面（terminal_exec music）+ metadata 编辑

日期：2026-02-18

## Vision（引用）

- PRD：`docs/prd/PRD-0029-music-player.md`
- 本轮目标（v30）：在不改变 v29 UI 入口（仍在 Files）的前提下，交付一个可审计、可测试的 “堆外控制面”：`terminal_exec music ...` 查询/控制播放状态；并提供受控的 metadata 读取/写入命令，为后续 Agent 自动化（脚本化控播/批量改 tag）打基础。

## Milestones

### M1：`terminal_exec music` 状态与控播

- PRD Trace：
  - PRD-0029：REQ-0029-040 / REQ-0029-041 / REQ-0029-042
- DoD（硬口径）：
  - `terminal_exec` 可执行：`music status/play/pause/resume/stop/seek/next/prev`；
  - stdout/result 结构化且可被 agent 稳定消费；
  - 每次执行可审计落盘（run_id），并有 Robolectric 单测覆盖成功/失败路径；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0。

### M2：metadata 读取/写入（带 confirm 门禁）

- PRD Trace：
  - PRD-0029：REQ-0029-050 / REQ-0029-051 / REQ-0029-052
- DoD（硬口径）：
  - `music meta get` 输出结构化 metadata（与 UI 口径一致）；
  - `music meta set` 覆盖尽可能完整的字段范围（含 lyrics/coverArt 等），且必须 `--confirm`；
  - tag 版本默认尽量保留；若升级/转换，必须在 result 里显式返回 before/after；
  - 写入采用临时文件 + 原子替换（失败不损坏原文件）；
  - 单测覆盖：非法 mp3、缺 confirm、写入失败回滚。

## Plan Index

- `docs/plan/v30-music-player-cli.md`

## ECN Index

- （本轮无）
