# v44 Plan：实时翻译 MVP（AudioTee + 伪流式 ASR + 流式字幕 + `radio live`）

## Goal

在不做 TTS/混音（v45）的前提下交付“边听边看字幕”的实时翻译：

- 从正在播放的 Radio 音频分叉 PCM（不影响原声）
- 5–10s buffer → Whisper → segments → LLM 翻译 → UI 滚动字幕
- CLI：`radio live start|stop|status`（受控输入）

## PRD Trace

- PRD-0034：REQ-0034-150 / REQ-0034-151 / REQ-0034-152 / REQ-0034-153

## Scope

做（v44）：

- `AudioTee`（ForwardingAudioSink / 等价方案）
- `AsrStreamProcessor`（伪流式：buffer→encode→Whisper）
- `LiveTranslationProcessor`（聚合 segments，滑窗上下文，SSE/streaming 翻译）
- UI：播放器页内字幕区（原文+译文）+ 可解释状态（识别中断/正在识别）
- CLI：`radio live ...`（可先只支持 `subtitle_only`）

不做（v44）：

- 不做译文语音输出与混音（v45）
- 落盘可选（可先默认不落盘；若做必须有 Settings 提示与清空入口）

## Acceptance（硬 DoD）

- 延迟：正常网络下字幕更新端到端延迟目标 ≤10 秒（允许轻微抖动；失败要可解释）。  
- 稳定：开启 live 后原声播放稳定（不得明显卡顿/爆音/崩溃）。  
- CLI：`radio live start --in <.radio> --source_lang xx --target_lang yy` 能启动并 `status` 可查询。  

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机：选择一个稳定电台，开启 live，观察字幕滚动与失败提示

## Files（规划）

- AudioTee：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/TeeAudioSink.kt`
- Streaming processors：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/AsrStreamProcessor.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveTranslationProcessor.kt`
- UI：
  - 播放器相关 UI 文件（按工程现状落点）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（新增 `live`）

## Steps（Strict / TDD）

1) Analysis：明确音频 tap 点（AudioSink vs AudioProcessor），与当前播放器链路的兼容约束。  
2) TDD Red：为 `AsrStreamProcessor` 的 buffer 策略写单测（输入 PCM chunks → 触发“应调用 ASR”的窗口边界）。  
3) TDD Green：实现 AudioTee 与 processor glue，先用 fake ASR 输出跑通 UI。  
4) TDD Red：翻译流的单测（输入 segments → 输出 translated segments，断言滑窗上下文与错误处理）。  
5) TDD Green：接入真实 ASR + LLM（仅在可控开关下），并补充错误码/状态展示。  
6) Verify：UT 全绿；真机冒烟（字幕可用，原声不受明显影响）。  

## Risks

- 设备兼容性：不同 ROM/设备的 AudioSink 行为可能差异大；需要预留 fallback（例如无法 tee 时禁用 live 并解释）。  

