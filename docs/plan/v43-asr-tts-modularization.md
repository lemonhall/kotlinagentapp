# v43 Plan：ASR/TTS 模块化（AsrService/TtsService + Chat 集成 + 并发隔离）

## Goal

把 ASR/TTS 从“点状能力”抽象为可复用的独立模块，为 v44/v45 实时管线与 Chat 语音交互打基础：

- `AsrService`：离线文件转录 + 流式（伪流式）转录
- `TtsService`：单段合成 +（可选）流式合成
- 并发隔离与优先级：Chat > Radio（不互相拖死）

## PRD Trace

- PRD-0034：REQ-0034-130 / REQ-0034-131 / REQ-0034-132

## Scope

做（v43）：

- 新增 `asr/` 与 `tts/` 模块（Kotlin package 层面的模块化即可，不强制 Gradle module）
- provider 抽象 + Settings 可配置默认 voice（按语言）
- 并发隔离：
  - `Semaphore(maxConcurrent=3)`（或等价限流）
  - Chat 请求优先（可通过不同 dispatcher/队列策略实现）
- Chat 页签“语音输入/语音输出”的最小集成（不必做复杂 UI）

不做（v43）：

- 不做完整实时翻译 UI（v44）
- 不做“全语音聊天产品化”（先把模块与最小入口打通）

## Acceptance（硬 DoD）

- AsrService：`transcribeFile()` 与 `transcribeStream()` 均有可测试实现（mock provider + 错误码归一）。  
- TtsService：`synthesize()` 可用；默认 voice 可从 Settings 读取。  
- 并发隔离：当 Radio 管线在跑时，Chat 仍可执行语音输入/输出（至少不报错、不卡死；优先级策略可解释）。  

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`

## Files（规划）

- ASR：
  - `app/src/main/java/com/lsl/kotlin_agent_app/asr/AsrService.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/asr/CloudAsrService.kt`
- TTS：
  - `app/src/main/java/com/lsl/kotlin_agent_app/tts/TtsService.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/tts/CloudTtsService.kt`
- Settings：
  - 现有 settings 结构中增加 ASR/TTS provider 与 voice 配置项
- Chat 集成：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/OpenAgenticSdkChatAgent.kt`（或 UI 层入口，视工程现状）

## Steps（Strict / TDD）

1) Analysis：定接口（方法签名、错误码、超时/取消语义），并确定 Settings 的最小配置表。  
2) TDD Red：mock provider 下的 AsrService/TtsService 单测（成功/失败/超时/取消）。  
3) TDD Green：实现 Cloud provider（Whisper/TTS）薄封装与错误映射。  
4) TDD Red：并发隔离单测（同一时间多请求不会爆炸；Chat 优先策略至少可观测）。  
5) TDD Green：接入 Chat 的最小语音入口（可先隐藏在 debug 开关下）。  
6) Verify：UT 全绿；最小真机冒烟（语音输入→文本，文本→语音播报）。  

## Risks

- provider 选择与费用：默认必须是“可关”的，并在 UI 明示会发送音频到云端。  

