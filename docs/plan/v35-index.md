# v35 Index：Android 本机 TTS CLI（terminal_exec tts）

日期：2026-02-19

## Vision（引用）

- PRD：`docs/prd/PRD-0032-android-tts-cli.md`
- 方法论引用：`tashan-development-loop`（PRD ↔ plan ↔ tests ↔ code 可追溯）、`paw-cli-add-workflow`（白名单 pseudo CLI：无 shell / 可审计落盘 / help 必须可用 / TDD）
- 本轮目标（v35）：在 `terminal_exec` 下新增顶层命令 `tts`，实现 `voices/speak/stop` 最小闭环，并提供内置 skill `android-tts` 作为可复用说明书。

## Milestones

### M1：`terminal_exec tts` 最小闭环（help/voices/speak/stop + await/timeout + TestHooks）

- PRD Trace：
  - PRD-0032：REQ-0032-001 ~ REQ-0032-005 / REQ-0032-010
- DoD（硬口径）：
  - `TerminalCommands.defaultRegistry(...)` 注册 `tts` 顶层命令；
  - `tts --help` / `tts help` / `tts <sub> --help` / `tts help <sub>` 全部可用，且 `exit_code=0`；
  - `tts voices` 可用，且 `result.voices_count >= 1`（在 Fake runtime 测试中用可控数据验证）；
  - `tts speak --text "..."` 可用，返回 `result.utterance_id`（非空）；
  - `tts stop` 可用；
  - `tts speak --await true --timeout_ms N` 超时必须返回 `error_code="Timeout"`；
  - 内置 skill `android-tts` 随 App 初始化安装到 `.agents/skills/android-tts`；
  - Verify：`.\gradlew.bat :app:testDebugUnitTest` exit code=0（覆盖：help + 参数校验 + voices/speak/stop + await timeout）。

## Plan Index

- `docs/plan/v35-android-tts-cli.md`

## ECN Index

- （本轮无）

## Review（Evidence）

- Unit tests：`.\gradlew.bat :app:testDebugUnitTest`（待补）

