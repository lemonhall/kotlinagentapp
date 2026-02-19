# v35 Plan：Android 本机 TTS CLI（terminal_exec tts）

日期：2026-02-19  
PRD：`docs/prd/PRD-0032-android-tts-cli.md`

## Goal

交付一个可审计、可测试、可控的本机朗读能力：通过 `terminal_exec` 白名单命令 `tts` 暴露 Android TextToSpeech（TTS），并提供内置 skill 文档指导 Agent 正确调用。

## PRD Trace

- PRD-0032：
  - REQ-0032-001（help 约定）
  - REQ-0032-002（voices）
  - REQ-0032-003（speak + await/timeout）
  - REQ-0032-004（stop）
  - REQ-0032-005（单测覆盖）
  - REQ-0032-010（内置 skill）

## Scope

### In Scope

- 新增 `terminal_exec tts` 顶层命令与 3 个子命令：`voices` / `speak` / `stop`
- 结构化输出（result JSON）+ 稳定错误码
- `--await true` + `--timeout_ms` 的超时行为（可验证）
- 通过 TestHooks 注入 Fake runtime，确保 Robolectric 单测稳定
- 新增内置 skill：`android-tts`

### Out of Scope

- TTS 导出音频文件
- SSML
- STT（语音识别）与录音
- 常驻前台服务/通知集成

## Acceptance（硬口径）

1) help：
- `tts --help` / `tts help` / `tts speak --help` / `tts help speak` 均 `exit_code=0`

2) voices：
- `tts voices` 返回 `exit_code=0`
- `result.command="tts voices"`
- `result.voices_count >= 1`（在单测 Fake runtime 中验证）

3) speak：
- `tts speak --text "你好"` 返回 `exit_code=0`
- `result.command="tts speak"`
- `result.utterance_id` 非空字符串

4) stop：
- `tts stop` 返回 `exit_code=0`
- `result.command="tts stop"`

5) timeout：
- `tts speak --text "hi" --await true --timeout_ms 1` 返回 `exit_code!=0` 且 `error_code="Timeout"`

6) Verify：
- `.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Files（预期改动路径）

- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/tts/TtsCommand.kt`（新增）
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/tts/*`（新增：runtime/provider）
- `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- `app/src/main/assets/builtin_skills/android-tts/SKILL.md`（新增）
- `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict: Red → Green → Refactor）

1) **TDD Red**：在 `TerminalExecToolTest.kt` 增加用例（先失败）
   - `tts --help` exit 0
   - `tts voices` 返回 `voices_count`（Fake runtime）
   - `tts speak` 缺 `--text` 返回 `InvalidArgs`
   - `tts speak --text ...` 返回 `utterance_id`
   - `tts speak --await true --timeout_ms 1` 返回 `Timeout`
   - `tts stop` 返回 ok

2) **TDD Green**：实现 `TtsCommand` + `TtsRuntimeProvider` + Fake hooks
   - 命令解析严格 argv（无 shell token）
   - 输出契约与错误码稳定

3) **注册白名单命令**：在 `TerminalCommands.defaultRegistry(...)` 注册 `TtsCommand`

4) **新增内置 skill**：`android-tts`
   - 写清楚 help/voices/speak/stop 的调用方式与可验证断言

5) **初始化安装**：在 `AgentsWorkspace.ensureInitialized()` 安装 `android-tts`

6) **Verify**：
   - `.\gradlew.bat :app:testDebugUnitTest`

## Risks & Mitigations

- Robolectric 不支持真实 TextToSpeech：通过 TestHooks 注入 Fake runtime，单测不依赖系统引擎。
- TTS 初始化异步导致竞态：Android runtime 用 mutex + init gate（实现阶段落地）。
- 音频焦点行为在不同 ROM 有差异：先固化为“请求失败即返回可解释错误码”，后续迭代再做降级策略。

