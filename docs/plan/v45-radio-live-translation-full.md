# v45 Plan：实时翻译完整版（TTS + 交替/仅译文 + 可选全链路落盘 + AudioFocus）

## Goal

在 v44 字幕基础上补齐“听译文”的完整体验与证据链：

- 译文 TTS 输出
- 两种模式：仅译文 / 原声降音量 + 译文交替
- 可选全链路落盘（音频切片 + ASR/译文 JSONL + TTS chunks）
- 与 Chat 并发：AudioFocusManager 优先级规则稳定可解释

## PRD Trace

- PRD-0034：REQ-0034-180 / REQ-0034-181 / REQ-0034-182

## Scope

做（v45）：

- `MixController`（调 ExoPlayer volume + 播放 TTS AudioTrack）
- `radio live` 扩展：
  - `--mode interleaved|target_only|subtitle_only`
  - `--save_audio/--save_transcript/--save_tts`（可选）
- 落盘：live 会话写入 `workspace/radio_recordings/live_*/`（含 `_meta.json/_STATUS.md` + JSONL + chunks）
- AudioFocusManager：Chat TTS > Radio TTS；必要时暂停/恢复 Radio TTS

不做（v45）：

- 不做复杂费用统计（可只给粗略提示；精确计费另立 PRD/任务）

## Acceptance（硬 DoD）

- 模式：两种模式均可用；切换模式不会导致崩溃或永久静音。  
- 落盘（若开启）：live 结束后目录内文件齐全且可被 Files 浏览；清空仍需二次确认。  
- 并发：Chat TTS 播放时 Radio TTS 按规则让路，结束后能恢复。  

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机：开启 live target_only + interleaved 各 2 分钟，观察音量切换与字幕稳定

## Files（规划）

- Mixing：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/MixController.kt`
- Persistence：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveSessionStore.kt`
- AudioFocus：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/AudioFocusManager.kt`（若已存在则扩展）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`

## Steps（Strict / TDD）

1) Analysis：定义两种模式的状态机（什么时候降音量、什么时候播 TTS、如何处理 TTS 来不及/缺段）。  
2) TDD Red：为 MixController 写单测（输入事件序列 → 断言 volume changes 与 TTS playback calls）。  
3) TDD Green：实现 target_only（更简单）跑通，再实现 interleaved。  
4) TDD Red：落盘开关与路径门禁测试（JSONL 追加写、chunks 命名、meta/state 更新）。  
5) TDD Green：接入真实落盘与 CLI flags。  
6) TDD Red：AudioFocus 优先级测试（Chat 触发时 Radio TTS pause/resume）。  
7) Verify：UT 全绿；真机冒烟（模式切换、落盘、与 Chat 并发）。  

## Risks

- 混音体验：不同耳机/扬声器下音量感受差异大；先保证“可用且可解释”，后续再调体验参数。  

