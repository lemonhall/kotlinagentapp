---
name: android-tts
description: 通过 `terminal_exec` 暴露 Android 本机 TextToSpeech（TTS）能力：`tts voices/speak/stop`（支持 --await/--timeout_ms）。
---

# android-tts（Android TextToSpeech / Pseudo CLI）

## Goal

在 App 内 `.agents` 工作区，通过 `terminal_exec` 使用白名单命令 `tts` 完成本机朗读（TTS）：

- `voices`：查看可用 voice / locale 概览
- `speak`：朗读指定文本（返回 `utterance_id`）
- `stop`：停止当前朗读与队列

## Commands

使用工具 `terminal_exec`，严格按单行命令调用（禁止 `;`、`|`、`>` 等 shell token）。

### 1) help（必须 exit 0）

- `tts --help`
- `tts help`
- `tts voices --help`
- `tts help voices`
- `tts speak --help`
- `tts help speak`
- `tts stop --help`
- `tts help stop`

期望：
- `exit_code=0`
- `result.ok=true`
- stdout 非空

### 2) voices（列出可用 voices）

- `tts voices`

（可选：限制输出/落盘）
- `tts voices --max 50`
- `tts voices --out artifacts/tts/voices.json`

期望：
- `exit_code=0`
- `result.command="tts voices"`
- `result.voices_count >= 1`（支持 TTS 的设备上）
- 若使用 `--out`：tool output 的 `artifacts[]` 包含 `.agents/artifacts/tts/voices.json`

### 3) speak（朗读文本）

- `tts speak --text "你好，柠檬叔。"`

（可选：指定 locale、语速/音高）
- `tts speak --text "你好" --locale zh-CN --rate 1.0 --pitch 1.0`

（可选：等待朗读结束；注意超时）
- `tts speak --text "你好" --await true --timeout_ms 20000`

期望：
- `exit_code=0`
- `result.command="tts speak"`
- `result.utterance_id` 非空

### 4) stop（停止朗读）

- `tts stop`

期望：
- `exit_code=0`
- `result.command="tts stop"`

## Safety / Guardrails（必须）

- `--text` 必填且不得为空；过长文本会被拒绝（`error_code="InvalidArgs"`）。
- `--locale` 使用 BCP47（例如 `zh-CN`、`en-US`）；不支持的语言会失败（通常 `error_code="NotSupported"`）。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接把错误信息说明给用户并停止。

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- 不要把敏感信息（token/密码）放进 `--text`（TTS 会读出来，且命令会被审计落盘）。

