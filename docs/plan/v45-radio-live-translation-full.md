# v45 Plan：实时翻译管线 + MixController + 三模式混音

## Goal

交付"边听电台边实时翻译"的核心体验闭环：

- 电台音频流 → 实时 ASR → 实时翻译 → TTS 合成 → 混音播放
- 三种模式：交替（原声降音量 + 译文朗读）/ 仅译文（原声静音 + 译文朗读）/ 仅字幕（原声正常 + 不播 TTS）
- 实时字幕追加（复用 v42 SubtitleScreen，新增 streaming 追加模式）

## 实时翻译管线架构

```
RadioAudioStream（ExoPlayer 正在播放的电台）
  → AudioSplitter（独立 Player 拉同一 stream URL，每 5 秒切一段 PCM）
  → AsrService.transcribeStream(channel=RADIO_A/B)
  → TranslationClient.translateBatch(segments, context)
  → TtsService.synthesize(translatedText, channel=RADIO_A/B)  # subtitle_only 模式跳过
  → MixController（混音输出）                                    # subtitle_only 模式不参与
  → SubtitleScreen（实时追加 segment）
```

### AudioSplitter 技术方案

v45 采用"独立 Player 旁路解码"方案（与 v39 录制架构一致）：

```
┌──────────────────────────────────────────────────┐
│                  同一个 stream URL                 │
│                                                   │
│  ┌─────────────────┐    ┌──────────────────────┐ │
│  │ ExoPlayer (主)    │    │ Media3 Player (旁路)  │ │
│  │ 负责：播放原声     │    │ 负责：解码 → PCM      │ │
│  │ 输出：扬声器       │    │ 输出：AudioSplitter   │ │
│  │ 音量：受 Mix 控制  │    │ 音量：静音（不出声）    │ │
│  └─────────────────┘    └──────────────────────┘ │
│                                                   │
│  两个 Player 独立拉流，互不干扰                      │
│  代价：双倍带宽；收益：零侵入主播放器，技术风险最低    │
└──────────────────────────────────────────────────┘
```

设计要点：

- 旁路 Player 使用独立的 Media3 ExoPlayer 实例，配置为"仅解码不播放"（自定义 `AudioSink` 将 PCM 数据导出而非送往硬件）
- 旁路 Player 拉取与主 Player 相同的 stream URL，两者之间无数据依赖
- PCM 数据按 5 秒窗口切段，与 v44 伪流式 ASR 的窗口对齐
- 旁路 Player 的生命周期由 `LiveTranslationPipeline` 管理：`radio live start` 时创建，`radio live stop` 时销毁
- 与 v39 录制的 `RecordingSession` 架构一致（v39 也是独立 Player 解码 → PCM → 编码），降低认知负担和代码复用成本

备选方案（记录但不采用）：

- 方案 B：自定义 `AudioSink` 注入主 ExoPlayer 的 `RenderersFactory`，从主播放器的渲染管线中截取 PCM。优点是单路带宽；缺点是侵入主播放器架构，影响播放稳定性，且 ExoPlayer 的 AudioSink 接口在不同版本间有变化，维护成本高。Analysis 阶段评估后决定不采用。

### 延迟预算

| 环节 | 预估延迟 | 说明 |
|------|----------|------|
| PCM 攒段 | 5 秒 | 固定窗口，不可压缩 |
| ASR（伪流式） | 2-5 秒 | Whisper API 单次调用 |
| 翻译（LLM） | 1-3 秒 | 单批 1-3 segments |
| TTS 合成 | 1-2 秒 | 单段文本（subtitle_only 模式无此环节） |
| 端到端（含 TTS） | 9-15 秒 | 用户听到译文时，原声已过去约 10-15 秒 |
| 端到端（subtitle_only） | 8-13 秒 | 字幕出现时，原声已过去约 8-13 秒 |

这个延迟对"语言学习场景"可接受（非同声传译场景）。UI 上显示延迟指示器，让用户知道当前译文对应多少秒前的原声。

另外，由于主 Player 和旁路 Player 独立拉流，两者之间存在 0-3 秒的流位置偏差（取决于 CDN 缓存和连接时机）。这个偏差会叠加到端到端延迟中，但对学习场景影响可忽略。`radio live status` 的 `pipeline_latency_sec` 会反映实际观测到的总延迟。

## PRD Trace

- PRD-0034：REQ-0034-180 / REQ-0034-181

## Scope

做（v45）：

- `LiveTranslationPipeline`：串联 AudioSplitter → ASR → Translation → TTS 的管线编排器
- `AudioSplitter`：独立 Media3 Player 旁路解码 + 每 5 秒切一段 PCM
- `MixController`：控制原声音量 + 播放 TTS 音频，三种模式的状态机
- `radio live start|stop|status`：CLI 最小闭环
- v42 `SubtitleScreen` 扩展：streaming 追加模式（新 segment 实时追加到底部，自动滚动）
- 延迟指示器：字幕视图顶部显示"译文延迟 ~12s"

不做（v45）：

- 不做全链路落盘（v46 的 `--save_audio` flag 将提供 live + 录制的等效能力）
- 不做 AudioFocusManager 与 Chat 并发治理（v46）
- 不做复杂费用统计

## 三种模式对比

| mode | 原声 | TTS | 字幕 | API 消耗 | 适用场景 |
|------|------|-----|------|----------|----------|
| `interleaved` | duck 到 20% | ✅ | ✅ | ASR + LLM + TTS | 沉浸式听译 |
| `target_only` | 静音 | ✅ | ✅ | ASR + LLM + TTS | 纯译文听力 |
| `subtitle_only` | 100% | ❌ | ✅ | ASR + LLM | 只看字幕学习，省 TTS 费用 |

`subtitle_only` 模式下，`LiveTranslationPipeline` 在翻译完成后直接将 segment 推送到 `SubtitleScreen`，跳过 TTS 合成和 MixController 环节。

## MixController 状态机

### 交替模式（interleaved）

```
PLAYING_ORIGINAL（原声 100% 音量）
  │
  ├─ TTS 就绪 ──→ DUCKING（原声降至 20% 音量）
  │                  │
  │                  ├─ 播放 TTS
  │                  │
  │                  └─ TTS 播放完毕 ──→ PLAYING_ORIGINAL（原声恢复 100%）
  │
  └─ TTS 来不及（超时 or 管线延迟）──→ 保持 PLAYING_ORIGINAL
     跳过该 segment 的 TTS，等下一个就绪时恢复正常流程
```

### 仅译文模式（target_only）

```
MUTED_ORIGINAL（原声静音）
  │
  ├─ TTS 就绪 ──→ PLAYING_TTS（播放译文）
  │                  │
  │                  └─ TTS 播放完毕 ──→ WAITING（等待下一段，保持静音）
  │
  └─ TTS 来不及 ──→ WAITING（短暂静音，不恢复原声）
```

### 仅字幕模式（subtitle_only）

MixController 不参与。原声保持 100% 音量，无 TTS 播放。管线在翻译完成后直接输出到字幕视图。

### "来不及"的降级策略

```
每个 segment 有一个 deadline = segment.endSec + maxLatencyBudget(15s)

如果 TTS 在 deadline 前未就绪：
  - 标记该 segment 为 skipped
  - 字幕视图仍然显示文字（灰色标记"未朗读"）
  - 继续处理下一个 segment

连续 3 个 segment 被 skip：
  - 触发 PipelineStalled 警告（UI toast + _STATUS.md 记录）
  - 不自动停止（用户可能网络恢复后继续）

连续 10 个 segment 被 skip：
  - 自动暂停管线，提示用户"翻译管线持续超时，已暂停"
  - 用户可手动恢复
```

注意：`subtitle_only` 模式下不存在 TTS "来不及"的问题，降级策略仅适用于 `interleaved` 和 `target_only`。但 ASR 或翻译环节的连续失败仍然触发 `PipelineStalled`。

```kotlin
class MixController(
    private val radioPlayer: ExoPlayer,
    private val ttsPlayer: AudioTrack,
) {
    enum class Mode { INTERLEAVED, TARGET_ONLY, SUBTITLE_ONLY }

    sealed class State {
        object PlayingOriginal : State()        // interleaved: 原声正常
        object Ducking : State()                // interleaved: 原声降音量 + TTS 播放中
        object MutedOriginal : State()          // target_only: 原声静音
        object PlayingTts : State()             // target_only: TTS 播放中
        object Waiting : State()                // target_only: 等待下一段
        object Passthrough : State()            // subtitle_only: 原声直通，不干预
    }

    fun setMode(mode: Mode)
    fun onTtsReady(segment: TranslatedSegment, ttsAudio: File)
    fun onTtsSkipped(segment: TranslatedSegment)
    fun stop()

    val state: StateFlow<State>
    val currentOriginalVolume: StateFlow<Float>  // 0.0 ~ 1.0
}
```

## `radio live` 与 `radio record` 的关系

| 场景 | 允许？ | 说明 |
|------|--------|------|
| `radio live` 单独运行 | ✅ | 实时翻译，不录制 |
| `radio record` 单独运行 | ✅ | 纯录制，不翻译 |
| 同一电台同时 `live` + `record` | ❌ 互斥 | 避免三路拉流（主播放 + live 旁路 + record 旁路） |
| 不同电台分别 `live` 和 `record` | ❌ | `live` 并发上限 1 路（资源消耗大） |

> v46 补充：v46 的 `radio live start --save_audio` flag 将提供"边听边实时翻译 + 同时保存原声"的等效能力，无需同时运行 `radio record`。`--save_audio` 复用 live 管线的旁路 Player PCM 数据直接编码落盘，不额外拉流。

并发限制：

- `radio live` 全局最多 1 路（实时翻译链路已经占用 1 条 Radio 通道 + 旁路 Player 带宽 + ASR/LLM/TTS API 并发）
- `radio live` 运行时，`radio record` 不可启动（反之亦然，对同一电台）
- `radio live` 占用 v44 的一条 Radio 通道（RADIO_A 或 RADIO_B），剩余一条通道仍可用于其他电台的离线转录任务

### 与 v44 通道隔离的关系

`radio live` 启动时通过 `ChannelDispatcher.assignRadioChannel(liveSessionId)` 获取一条 Radio 通道。管线内的 ASR / Translation / TTS 请求全部走这条通道，通道内串行处理。Chat 通道不受影响。

## 实时字幕视图扩展

v42 的 `SubtitleScreen` 新增 `StreamingMode`：

```kotlin
enum class SubtitleMode {
    STATIC,     // v42: 加载完整 translation.json，可上下滚动
    STREAMING,  // v45: 实时追加，新 segment 出现在底部，自动滚动
}
```

streaming 模式下：

- 新 segment 追加到列表底部，带淡入动画
- 自动滚动到最新 segment（用户手动上滑时暂停自动滚动，点击"回到最新"恢复）
- 顶部显示延迟指示器：`译文延迟 ~12s`（基于最新 segment 的 startSec 与当前播放位置的差值）
- 被 skip 的 segment 显示文字但标记为灰色 + "未朗读"标签（仅 interleaved/target_only 模式）

## CLI

```
radio live start --station <stationId> --mode interleaved|target_only|subtitle_only \
  --source_lang ja --target_lang zh
radio live stop
radio live status
```

`radio live start` 的 result：

```json
{
  "session_id": "live_20260219_2000_a1b2c3",
  "station_id": "nhk_world",
  "mode": "interleaved",
  "source_language": "ja",
  "target_language": "zh",
  "state": "starting",
  "pipeline_latency_sec": null,
  "radio_channel": "RADIO_A",
  "message": "Live translation starting..."
}
```

`radio live status` 的 result：

```json
{
  "session_id": "live_20260219_2000_a1b2c3",
  "state": "running",
  "mode": "interleaved",
  "uptime_sec": 120,
  "segments_processed": 24,
  "segments_skipped": 1,
  "pipeline_latency_sec": 12.3,
  "radio_channel": "RADIO_A",
  "asr_provider": "openai_whisper",
  "translation_provider": "openai_gpt4",
  "tts_provider": "openai_tts"
}
```

注意：`subtitle_only` 模式下 `tts_provider` 为 `null`，`segments_skipped` 仅统计 ASR/翻译失败导致的 skip（不含 TTS skip）。

## 错误码集合

| error_code | 含义 |
|------------|------|
| `LiveSessionAlreadyActive` | 已有一个 live 会话在运行（全局限 1 路） |
| `StationNotPlaying` | 指定电台当前未在播放 |
| `RecordingConflict` | 该电台正在录制中，与 live 互斥 |
| `NoChannelAvailable` | v44 的 2 条 Radio 通道都被占用（不应出现，因为 live 限 1 路，但作为防御性错误码保留） |
| `AsrPipelineStalled` | ASR 连续 10 次超时，管线自动暂停 |
| `TranslationPipelineStalled` | 翻译连续 10 次失败，管线自动暂停 |
| `TtsPipelineStalled` | TTS 连续 10 次失败，管线自动暂停（subtitle_only 模式不会触发） |
| `PipelineWarning` | 连续 3 次 skip，警告（非致命） |
| `InvalidMode` | mode 参数不合法 |
| `AsrDisabled` | ASR 未启用（Settings） |
| `TtsDisabled` | TTS 未启用（Settings）；subtitle_only 模式不检查此项 |

## Acceptance（硬 DoD）

- 管线串联：`radio live start` 后，电台音频流经旁路 Player → PCM → ASR → 翻译 → TTS → 混音输出，端到端可用。
- 旁路 Player：live 启动时创建独立 Media3 Player 拉取同一 stream URL，live 停止时销毁；主 Player 播放不受影响。
- 交替模式：TTS 播放时原声降至 20% 音量，TTS 结束后恢复 100%；切换模式不崩溃不永久静音。
- 仅译文模式：原声静音，只听到 TTS 译文朗读。
- 仅字幕模式：原声保持 100% 音量，不播放 TTS，字幕正常追加；不消耗 TTS API。
- 来不及降级：TTS 未就绪时跳过该 segment，字幕仍显示文字；连续 10 次 skip 自动暂停并提示。
- 延迟指示器：字幕视图顶部显示当前管线延迟（秒）。
- 实时字幕：新 segment 实时追加到字幕视图底部，自动滚动。
- 并发限制：第二个 `radio live start` 必须返回 `LiveSessionAlreadyActive`。
- 互斥：对正在录制的电台执行 `radio live start` 必须返回 `RecordingConflict`。
- 通道占用：`radio live` 占用一条 Radio 通道，`radio live status` 的 result 中体现 `radio_channel`。
- TTS 门禁：`interleaved` 和 `target_only` 模式在 `tts.enabled=false` 时返回 `TtsDisabled`；`subtitle_only` 模式不检查 TTS 开关。
- CLI help：`radio live --help` 为 0。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机：开启 live interleaved + target_only + subtitle_only 各 2 分钟，观察音量切换、字幕追加、延迟指示器

## Files（规划）

- 实时翻译管线：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveTranslationPipeline.kt`（管线编排器）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/AudioSplitter.kt`（独立 Player 旁路解码 + PCM 切段）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/MixController.kt`（混音状态机）
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_live/LiveSession.kt`（会话状态）
- 字幕视图扩展：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleScreen.kt`（新增 STREAMING 模式）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleViewModel.kt`（新增实时追加逻辑）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/LatencyIndicator.kt`（延迟指示器组件）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（新增 `live` 子命令）
- Tests：
  - `MixController` 状态机单测（三种模式 × 正常/来不及/连续 skip）
  - `LiveTranslationPipeline` 管线编排单测（mock ASR/Translation/TTS，验证串联顺序与超时处理；subtitle_only 模式验证跳过 TTS）
  - `AudioSplitter` 切段单测（mock Media3 Player PCM 输出，验证 5 秒切段）
  - CLI argv 门禁 + 并发限制 + 互斥校验 + 通道分配校验

## Steps（Strict / TDD）

1) Analysis：确定旁路 Player 的 `AudioSink` 实现方案（自定义 `AudioSink` 将 PCM 导出到 `AudioSplitter` 的 buffer，不送硬件）；确定延迟预算各环节的超时阈值；确定 MixController 的音量曲线（线性 vs 渐变）；在目标设备（Nova 9）上做旁路 Player PoC 验证（独立 Player 拉同一 stream URL + 自定义 AudioSink 导出 PCM）。
2) TDD Red：`MixController` 状态机单测 — interleaved 模式：TTS 就绪 → ducking → TTS 完毕 → 恢复；TTS 来不及 → 保持原声；连续 3 次 skip → 警告；连续 10 次 → 暂停。
3) TDD Red：`MixController` 状态机单测 — target_only 模式：TTS 就绪 → 播放 → 等待；来不及 → 静音等待。
4) TDD Red：`MixController` 状态机单测 — subtitle_only 模式：状态始终为 Passthrough；`onTtsReady` / `onTtsSkipped` 不改变状态；原声音量始终 1.0。
5) TDD Green：实现 `MixController`。
6) TDD Red：`LiveTranslationPipeline` 编排单测 — mock 全部下游（含 mock AudioSplitter），验证 PCM 段 → ASR → Translation → TTS → MixController 的调用顺序与数据传递；subtitle_only 模式验证 TTS 和 MixController 不被调用；验证通道分配（`assignRadioChannel`）。
7) TDD Red：`AudioSplitter` 单测 — mock Media3 Player 的 PCM 输出，验证每 5 秒产出一段；验证 `start()`/`stop()` 生命周期正确创建/销毁旁路 Player。
8) TDD Green：实现 `AudioSplitter`（独立 Player + 自定义 AudioSink + 5 秒切段）+ `LiveTranslationPipeline`。
9) TDD Red：CLI `radio live` argv 门禁 + `LiveSessionAlreadyActive` + `RecordingConflict` + `NoChannelAvailable` + `TtsDisabled`（仅 interleaved/target_only）+ subtitle_only 不检查 TTS 开关。
10) TDD Green：实现 CLI + 并发/互斥校验。
11) TDD Green：扩展 `SubtitleScreen` streaming 模式 + 延迟指示器。
12) Verify：UT 全绿；真机冒烟（三种模式各 2 分钟；确认旁路 Player 不影响主播放器音质和稳定性）。

## Risks

- 旁路 Player 带宽：双路拉流意味着双倍带宽消耗。对 WiFi 环境影响可忽略；移动数据环境下需要在 UI 上提示用户。建议 `radio live start` 时检测网络类型，移动数据下弹确认提示。
- 旁路 Player 与主 Player 的流位置偏差：两个独立连接拉同一个直播流，CDN 返回的起始位置可能有 0-3 秒差异。这个偏差会叠加到管线延迟中，但对学习场景可接受。不做主动同步（复杂度高、收益低）。
- 端到端延迟：9-15 秒（+ 0-3 秒流偏差）的延迟在学习场景可接受，但如果 ASR/LLM 响应波动大，可能偶尔超过 20 秒。降级策略（skip）是兜底，但频繁 skip 会严重影响体验。
- API 费用：实时翻译每分钟消耗 ASR + LLM + TTS 三个 API 调用（subtitle_only 模式省去 TTS），费用远高于离线模式。建议 UI 上显示"预估费用"或至少在启动时提示。
- 电池消耗：双路拉流 + 持续的网络请求 + 音频处理对移动设备电池压力大，需要在真机测试中观察。
- 旁路 Player 的 AudioSink 自定义：虽然不侵入主 Player，但自定义 AudioSink 仍需要正确处理 Media3 的 AudioSink 接口契约（`handleBuffer`/`flush`/`reset` 等）。Analysis 阶段的 PoC 必须覆盖：正常播放、流中断重连、codec 切换等场景。