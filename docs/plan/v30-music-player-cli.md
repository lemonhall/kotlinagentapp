# v30 Plan：MP3 播放器 CLI 控制面（terminal_exec music）+ metadata 编辑

## Goal

在 v29 播放器运行时基础上，按 `paw-cli-add-workflow` 给 `terminal_exec` 增加顶层命令 `music`，让 agent/人类可用 CLI 方式查询/控制播放状态；并提供受控的 metadata 读取/写入能力（带 `--confirm` 门禁 + 原子写入），形成“UI 播放器 + CLI 控制面”职责清晰的双入口。

## PRD Trace

- PRD-0029：REQ-0029-040 / REQ-0029-041 / REQ-0029-042
- PRD-0029：REQ-0029-050 / REQ-0029-051 / REQ-0029-052

## Scope

做：

- `terminal_exec` 新增顶层命令 `music`（遵守：无 shell / 白名单 / 可审计 / 可测试）：
  - `music status`
  - `music play --in "<agents-path>"`（仅允许 `.agents/workspace/musics/**` 下的 mp3；其他路径一律拒绝）
  - `music pause|resume|stop`
  - `music seek --to-ms 12345`
  - `music next|prev`
- metadata CLI：
  - `music meta get --in "<agents-path>"`
  - `music meta set --in "<agents-path>" --title ... --artist ... --lyrics ... --cover-art <path> --confirm`（字段集合以 PRD-0029: REQ-0029-051A 为准）
- 内置 skill（builtin_skills）：
  - `music-cli`（目录/命名以实现时定），说明书里强制实际调用 `terminal_exec`
- 单测（Robolectric）：
  - 覆盖成功路径 + 关键失败路径（invalid args / unknown file / confirm required / write rollback）

不做（v30）：

- 通过外部进程/系统 shell 实现任何能力（明确禁止）
- 大量 stdout 输出（超限一律落盘 artifacts 并返回引用）

## Command Protocol（按 paw-cli-add-workflow 固化）

统一输出：

- `stdout`：人类可读摘要（短）
- `result`：结构化 JSON（稳定字段，供 agent 消费）
- `artifacts[]`：必要时落盘（大输出、调试细节）
- `exitCode`：0/非 0
- `errorCode` / `errorMessage`：失败必须可解释

建议 `result` 最小字段（`music status`）：

- `state`: `idle|playing|paused|stopped|error`
- `track`: `{ path, title, artist, album }`（缺则为空或省略）
- `positionMs` / `durationMs`
- `queueIndex` / `queueSize`

## Files（规划：遵守 paw-cli-add-workflow）

- 注册表（只注册，不写实现）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（独立目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/music/*`
- 单测（优先扩）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- 内置 skill：
  - `app/src/main/assets/builtin_skills/music-cli/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict / TDD）

1) Analysis：锁定 `music` argv 协议（flags/位置参数/confirm 门禁）与 `status` 的 result schema（字段稳定）。  
2) TDD Red：先在 `TerminalExecToolTest.kt` 写命令级测试（status/play/stop/seek + invalid args + unknown file + confirm required）。  
3) TDD Green：实现 `MusicCommand`（只做路由/参数校验/结构化输出），并对接 v29 的 `MusicPlayerController`。  
4) metadata：补 `meta get/set`，并实现原子写入（临时文件 + 替换），跑到绿。  
5) 接入：注册命令到 `TerminalCommands.defaultRegistry(...)`；新增 builtin skill 并在 `AgentsWorkspace.ensureInitialized()` 安装。  
6) Verify：`.\gradlew.bat :app:testDebugUnitTest`。  

## Risks

- ID3 写入实现复杂度高：如选第三方库，需评估 Android 兼容性与体积；如自研，需严格测试原子写入与 tag 兼容。  
- CLI 与 UI 状态一致性：需定义“队列/当前曲目”的单一事实来源（controller），避免 CLI 改了但 UI 不刷新。  
- 仅允许 `musics/` 子树路径：可显著降低误操作风险，但需要清晰、可解释的错误信息（例如 `PathNotAllowed` / `NotInMusicsDir`）。
