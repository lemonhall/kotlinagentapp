---
name: music-cli
description: 通过 `terminal_exec` 控制/查询播放器状态（music status/play/pause/resume/stop/seek/next/prev）并读取/写入 mp3 metadata（meta get/set，写入必须 --confirm）。
---

# music-cli（Pseudo Music CLI）

## Goal

为 App 内置 MP3 播放器提供“堆外控制面”（可审计、可测试）：用 `terminal_exec` 执行 `music ...` 命令查询/控制播放状态，并在受控范围内读取/写入 ID3 metadata。

## 工作区约定（必须遵守）

- `music` 命令**只允许**播放 `workspace/musics/` 目录下的 `*.mp3` 文件。
- 不要扫描 `workspace/` 根目录；不要尝试播放 `m4a/wav/flac/ogg/aac` 等格式（当前命令不支持）。
- 用户未给出精确文件路径时，唯一允许的“找文件”方式是：`List workspace/musics`，然后让用户选择或按用户要求“随便来一个”。

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

## 播放流程（必须遵守）

### Step 0：确定要播放的 mp3 路径

1) 如果用户已经给出明确路径（例如 `workspace/musics/xxx.mp3`）：
   - 禁止 List/Glob/Grep 乱搜，直接使用该路径。
2) 如果用户没有给出路径（例如用户说“随便来一个/播放点音乐吧”）：
   - 先用 `List` 列目录：`workspace/musics`
   - 从列表中过滤出 `*.mp3`（大小写不敏感）
   - 若没有任何 `*.mp3`：
     - 直接告诉用户：请把一个 mp3 放到 `workspace/musics/` 下，然后把路径发给我（例如 `workspace/musics/test.mp3`），我才能播放。
   - 若有多个 `*.mp3`：
     - 用户说“随便来一个” → 选择列表里的第一个 mp3
     - 否则把候选（最多 12 个：序号 + 文件名/路径）列出来让用户选一个

### Step 1：播放并确认

1) 执行：`music play --in <workspace/musics/**.mp3>`
2) 立刻执行：`music status`，确认 `result.state=playing`

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
