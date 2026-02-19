# PRD-0032：Android TTS CLI（本机语音合成能力暴露）

日期：2026-02-19  
定位：在 Kotlin Agent App 内，把 Android 原生 TextToSpeech（TTS）能力以 `terminal_exec` 白名单命令形式暴露给 Agent（以及人类在 Terminal Tab 手动调用），形成“可审计、可验证、可控”的本机朗读能力。

> 备注：`v17` 已用于 Archive CLI（zip/tar）。本 PRD 对应的实现轮次建议从下一个可用版本号开始（例如 `v35`），以避免文档编号冲突。

## Vision

1) Agent 可通过 `terminal_exec` 调用本机 TTS 朗读一段文本（中文优先场景），并拿到稳定的结构化结果（JSON）。  
2) 人类可在 Terminal Tab 手动执行同样的命令，快速验证设备端 TTS 是否可用。  
3) TTS 行为必须“可停止、可限速、可控音频焦点”，避免失控噪音与打扰。  

## Background / Why

- 让 App 具备“把 Agent 的关键输出读出来”的能力（例如：提醒、简短摘要、计时器/日程提示），对移动端非常关键。
- 采用 `terminal_exec` 白名单路由（无 shell、无外部进程）可以保证：可审计落盘、可测、可控、可上架。

## Non-Goals（本轮范围外）

- STT（语音识别）、录音、唤醒词、连续对话等语音输入能力。
- 音频文件离线导出（TTS 合成到 wav/mp3 文件）与长音频播报（播客/有声书级别）。
- 与系统通知/闹钟的深度集成（Notification + 前台服务常驻等）。
- 复杂 SSML 支持（可作为后续迭代）。

## User Stories

- 作为用户，我希望让 Agent 把“很短的一段文本”读出来（比如 1~3 句），并且我能随时停止它。
- 作为开发者，我希望能在 Terminal 页签用一条命令验证：TTS 引擎是否可用、当前可用 voice/language 列表是什么。
- 作为产品，我希望避免“自动朗读”造成的尴尬或打扰；朗读必须来自明确指令触发，并且可被停止。

## Proposed CLI（通过 terminal_exec 暴露）

顶层命令：`tts`

### Help（强制）

- `tts --help` / `tts help` 必须返回 `exit_code=0`
- `tts speak --help`、`tts voices --help`、`tts stop --help` 同理

### 子命令（v1 最小闭环）

1) `tts voices`
   - 返回当前设备可用的 voices / locales 概览（尽量精简；超长用 artifacts 落盘）

2) `tts speak --text "<...>" [--locale <bcp47>] [--rate <0.1..2.0>] [--pitch <0.5..2.0>] [--queue flush|add] [--await true|false] [--timeout_ms <N>]`
   - 默认行为建议：
     - `--queue flush`（新朗读会打断旧队列）
     - `--await false`（默认不阻塞等待朗读完成；返回一个 `utterance_id`）
   - 若 `--await true`：阻塞等待“朗读完成/失败/超时”，并在 `timeout_ms` 到达后以可解释错误返回

3) `tts stop`
   - 停止当前朗读与队列

> 约束提醒：`terminal_exec` 明确禁止 `;`、`&&`、`|`、`>` 等 shell token；命令必须保持单行 argv 形式。

## Output Contract（TerminalCommandOutput）

所有子命令输出遵循：

- `exit_code=0` 表示成功；非 0 表示失败
- `stdout`：人类可读摘要（尽量短）
- `result`：结构化 JSON（给 Agent 稳定消费）
- `error_code` / `error_message`：失败必须可解释且稳定（便于 Agent 分支处理）

建议 error_code：

- `InvalidArgs`：参数缺失/格式错误/越界
- `NotSupported`：设备/ROM 不支持或 TTS 引擎不可用
- `TtsInitFailed`：TextToSpeech 初始化失败（含 engine 信息）
- `Timeout`：`--await true` 等待超时
- `Busy`：引擎忙且不接受新请求（如发生）

## Safety / UX Constraints（强制）

1) **禁止自动朗读**：只有当用户/Agent 明确调用 `terminal_exec` 的 `tts ...` 命令时才触发朗读。  
2) **可停止**：`tts stop` 必须可用，并且在 UI 上易于调用（至少可通过 Terminal Tab 执行）。  
3) **音频焦点**：朗读前请求音频焦点（Audio Focus），失败则返回可解释错误或降级策略（需在实现/计划中固定一种）。  
4) **长度上限**：对 `--text` 设置上限（例如 2k~10k 字符，具体实现以稳定性为准），超出返回 `InvalidArgs`，避免 OOM/卡死。  
5) **隐私**：stdout/stderr/result 不得回显任何用户 secrets（本 PRD 的 TTS 本身不需要 secrets，但必须延续项目安全约定）。  

## Acceptance（硬口径）

1) `tts --help` / `tts help` 可用，`exit_code=0`，stdout 提供清晰 usage。  
2) `tts voices`：
   - `exit_code=0`
   - `result.voices_count >= 1`（在支持 TTS 的设备上）
3) `tts speak --text "你好，柠檬叔。"`：
   - `exit_code=0`
   - 返回 `result.utterance_id`（非空字符串）
4) `tts stop`：
   - 在朗读进行中调用时，能停止当前朗读（以可验证的状态/回调为准，需在实现与测试里固定证据口径）
5) Tests：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（至少覆盖：help、参数校验、speak/stop 的最小行为与可解释错误码）

## Requirements（Req IDs）

- REQ-0032-001：新增 `terminal_exec` 白名单顶层命令 `tts`，并按项目约定实现 `--help/help`（含子命令 help，且 help 必须 `exit_code=0`）。
- REQ-0032-002：实现 `tts voices`，返回结构化 voice/locale 概览；超长输出需支持落盘 artifacts 引用。
- REQ-0032-003：实现 `tts speak`，支持 `--text` 与可选 `--locale/--rate/--pitch/--queue/--await/--timeout_ms`，并返回稳定的 `utterance_id`。
- REQ-0032-004：实现 `tts stop`，可停止当前朗读与队列，返回稳定的结构化结果。
- REQ-0032-005：为 `tts` 系列命令补 Robolectric 单测：覆盖正常路径 + 异常路径（InvalidArgs / NotSupported / TtsInitFailed / Timeout）。
- REQ-0032-010：新增内置 skill 文档（例如 `android-tts`）指导 Agent 正确调用 `terminal_exec` 的 `tts` 命令并进行可验证断言（stdout/result 字段）。

