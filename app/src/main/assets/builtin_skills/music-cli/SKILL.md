---
name: music-cli
description: 通过 `terminal_exec` 控制/查询播放器状态（music status/play/pause/resume/stop/seek/next/prev）并读取/写入 mp3 metadata（meta get/set，写入必须 --confirm）。
---

# music-cli（Pseudo Music CLI）

## Goal

为 App 内置 MP3 播放器提供“堆外控制面”（可审计、可测试）：用 `terminal_exec` 执行 `music ...` 命令查询/控制播放状态，并在受控范围内读取/写入 ID3 metadata。

## Commands（v30）

### 状态

使用工具 `terminal_exec` 执行：

- `music status`

期望：
- `exit_code=0`
- `result.state` 为 `idle|playing|paused|stopped|error`

### 播放控制（仅允许 musics/ 子树）

使用工具 `terminal_exec` 执行（示例）：

- `music play --in workspace/musics/demo.mp3`
- `music pause`
- `music resume`
- `music seek --to-ms 12345`
- `music next`
- `music prev`
- `music stop`

期望：
- `exit_code=0`
- `result.track.path`（如存在）为 `workspace/musics/**.mp3`

### metadata 读取/写入（写入必须 --confirm）

读取：
- `music meta get --in workspace/musics/demo.mp3`

写入（必须 `--confirm`）：
- `music meta set --in workspace/musics/demo.mp3 --title "t1" --artist "a1" --lyrics "l1" --confirm`

期望：
- `exit_code=0`
- `meta get` 的 `result.metadata.title/artist/lyrics` 反映写入结果

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- 所有写入类命令（`music meta set`）必须带 `--confirm`；缺失视为错误并停止。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；命令必须单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接报告错误并停止。

