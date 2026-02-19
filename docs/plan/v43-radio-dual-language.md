# v43 Plan：language-tutor Agent + TTS 双语听力生成

## Goal

在 v42 字幕视图基础上，交付语言学习的两个核心能力：

- 长按字幕句子唤起 language-tutor Agent，获得语法解析、词汇扩展、例句等
- 生成双语听力 TTS 音频（交替模式 / 仅译文模式）

## PRD Trace

- PRD-0034：REQ-0034-103 / REQ-0034-104

## Scope

做（v43）：

- 内置 language-tutor SKILL：
  - `app/src/main/assets/builtin_skills/language-tutor/SKILL.md`
  - 能力：语法解析、词汇扩展、例句生成、发音要点、文化背景
- 字幕视图长按交互 → 跳转 Chat 页签 + 自动切换 skill + 注入上下文（方案 B，复用现有 Chat 基础设施）
- TTS 抽象层：
  - `TtsClient` 接口（输入文本 + 语言 + voice，输出音频文件）
  - 第一个实现（OpenAI TTS 或 Android 系统 TTS，Analysis 阶段确定）
- TTS 双语听力任务系统：
  - 两种模式：仅译文（`target_only`）/ 交替（`interleaved`：原文朗读 → 1s 停顿 → 译文朗读）
  - 落盘到 `audio_bilingual/` 目录
- CLI：`radio tts start|status|cancel`

不做（v43）：

- 不做 mini chat 内嵌面板（方案 A，后续优化）
- 不做复杂学习系统（生词本/复习计划等）
- 不做实时翻译管线（v45+）

## language-tutor Agent

### SKILL.md 能力范围

- 语法解析：拆解句子结构，标注词性、时态、语法点
- 词汇扩展：生词释义、同义词、常用搭配
- 例句生成：围绕该语法点或词汇，生成 2-3 个难度递进的例句
- 发音要点：针对日语的音调、英语的连读弱读等
- 文化背景：必要时补充语境（如新闻用语的正式程度）

### 上下文注入

长按字幕中某个 segment 后，跳转 Chat 页签，自动：

1. 切换到 `language-tutor` skill
2. 注入结构化上下文作为首条消息：

```json
{
  "selectedSegment": {
    "sourceText": "今日のトップニュースをお伝えします",
    "translatedText": "为您播报今天的头条新闻",
    "sourceLanguage": "ja",
    "targetLanguage": "zh",
    "startSec": 3.2,
    "endSec": 7.8
  },
  "surroundingSegments": [
    {
      "sourceText": "こんにちは、NHKワールドニュースです",
      "translatedText": "你好，这里是NHK世界新闻"
    },
    {
      "sourceText": "まず、経済ニュースです",
      "translatedText": "首先是经济新闻"
    }
  ],
  "stationName": "NHK World",
  "userLevel": "intermediate"
}
```

3. Agent 自动生成第一条分析回复
4. 用户可继续追问（如"这个语法还有什么用法？"）

### 交互流程

```
字幕视图 → 长按 segment → 跳转 Chat 页签
  → 自动切换 language-tutor skill
  → 注入上下文 → Agent 自动回复分析
  → 用户可继续对话
  → 返回字幕视图（Chat 历史保留）
```

## TTS 抽象层

```kotlin
interface TtsClient {
    /**
     * 将文本合成为音频文件。
     * @return 生成的音频文件路径
     */
    suspend fun synthesize(
        text: String,
        language: String,
        voice: String,
        outputFile: File,
        outputFormat: String,   // "ogg" / "mp3"
    ): TtsResult
}

data class TtsResult(
    val outputFile: File,
    val durationSec: Double,
)
```

v43 交付第一个实现（Analysis 阶段确定选型：OpenAI TTS API 或 Android 系统 TTS）。后续可插入阿里云、火山引擎等。

## TTS 双语听力落盘结构

```
{task_id}/
  audio_bilingual/
    _task.json
    _STATUS.md
    chunk_001_bilingual.ogg
    chunk_002_bilingual.ogg
    ...
```

### _task.json Schema

```json
{
  "schema": "kotlin-agent-app/tts-bilingual-task@v1",
  "taskId": "tts_20260219_1700_m1n2o3",
  "parentTaskId": "tx_20260219_1600_x1y2z3",
  "mode": "interleaved",
  "sourceLanguage": "ja",
  "targetLanguage": "zh",
  "sourceTtsVoice": "ja-JP-Neural",
  "targetTtsVoice": "zh-CN-Neural",
  "outputFormat": "ogg",
  "state": "pending",
  "progress": {
    "totalChunks": 12,
    "completedChunks": 4,
    "failedChunks": 0
  },
  "createdAt": "2026-02-19T17:00:00+08:00",
  "updatedAt": "2026-02-19T17:25:00+08:00",
  "error": null
}
```

`state` 取值：`pending | in_progress | completed | failed | cancelled`

### 双语听力音频生成逻辑

交替模式（`interleaved`）：

```
对每个 segment:
  1. TTS 合成原文（sourceLanguage + sourceTtsVoice）→ 临时文件
  2. 插入 1 秒静音
  3. TTS 合成译文（targetLanguage + targetTtsVoice）→ 临时文件
  4. 插入 0.5 秒静音（segment 间隔）
拼接所有片段 → chunk_NNN_bilingual.ogg
```

仅译文模式（`target_only`）：

```
对每个 segment:
  1. TTS 合成译文（targetLanguage + targetTtsVoice）→ 临时文件
  2. 插入 0.5 秒静音（segment 间隔）
拼接所有片段 → chunk_NNN_bilingual.ogg
```

## CLI

```
radio tts start --task <transcriptTaskId> --mode interleaved|target_only \
  [--source_voice ja-JP-Neural] [--target_voice zh-CN-Neural]
radio tts status --task 

>
radio tts cancel --task <ttsTaskId>
```

`radio tts start` 的 result：

```json
{
  "task_id": "tts_20260219_1700_m1n2o3",
  "parent_task_id": "tx_20260219_1600_x1y2z3",
  "mode": "interleaved",
  "state": "pending",
  "total_chunks": 12,
  "message": "TTS task created, 12 chunks queued"
}
```

## 错误码集合

| error_code | 含义 |
|------------|------|
| `InvalidArgs` | 参数缺失或非法（如 mode 不合法） |
| `TranscriptTaskNotFound` | parentTaskId 对应的转录任务不存在 |
| `TranslationNotReady` | 转录任务尚未完成翻译（无 translation.json） |
| `TtsTaskAlreadyExists` | 该转录任务已有相同 mode + 语言对的 TTS 任务进行中 |
| `TtsNetworkError` | TTS API 网络不可达 |
| `TtsRemoteError` | TTS API 返回非 2xx |
| `TtsQuotaExceeded` | TTS API 配额耗尽 |
| `AudioConcatError` | 音频片段拼接失败 |

## Acceptance（硬 DoD）

- Agent 唤起：长按字幕视图中任意 segment，必须跳转 Chat 页签并自动切换到 language-tutor skill，上下文（选中句 + 周边句 + 语言对）正确注入。
- Agent 回复：注入上下文后 Agent 必须自动生成第一条分析回复（语法/词汇/例句至少覆盖其一）。
- 返回保留：从 Chat 返回字幕视图后，Chat 历史保留，可再次进入继续对话。
- TTS 落盘：`radio tts start` 必须创建 `audio_bilingual/_task.json` + `_STATUS.md`。
- TTS 产物：逐 chunk 产出 `chunk_NNN_bilingual.ogg`，可被 Android MediaPlayer 正常播放。
- TTS 模式：`interleaved` 模式产出的音频必须包含原文朗读 + 停顿 + 译文朗读；`target_only` 模式只包含译文朗读。
- CLI help：`radio tts --help` 为 0。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机：字幕视图长按 → Agent 面板可用；`radio tts start` → 产出可播放的双语音频

## Files（规划）

- 内置 skill：
  - `app/src/main/assets/builtin_skills/language-tutor/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`（注册 skill）
- 字幕视图长按交互：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleScreen.kt`（新增长按手势 + 跳转逻辑）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleViewModel.kt`（组装上下文）
- Chat 集成：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/chat/ChatViewModel.kt`（支持外部注入 skill + 上下文）
- TTS 抽象层（建议独立子包）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/tts/*`
    - `TtsClient.kt`（接口）
    - `TtsResult.kt`
    - `OpenAiTtsClient.kt` 或 `SystemTtsClient.kt`（第一个实现）
- TTS 双语听力任务：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_tts/*`
    - `BilingualTtsTaskManager.kt`
    - `BilingualTtsWorker.kt`（调用 TtsClient + 音频拼接）
    - `BilingualTtsTaskV1.kt`（kotlinx.serialization）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（新增 `tts` 子命令）
- Tests：
  - language-tutor 上下文组装测试
  - TTS mock 测试（synthesize + 拼接逻辑）
  - CLI argv 门禁测试

## Steps（Strict / TDD）

1) Analysis：确定 language-tutor SKILL.md 的 prompt 结构；确定 TTS 选型（OpenAI TTS vs Android 系统 TTS）；确定音频拼接方案（MediaCodec + MediaMuxer 拼接 OGG，或 PCM 拼接后统一编码）。
2) TDD Red：language-tutor 上下文组装测试 — 选中句 + 周边句（前后各 2 句）+ 语言对 + stationName 正确组装。
3) TDD Green：实现 SKILL.md + AgentsWorkspace 注册 + SubtitleViewModel 上下文组装。
4) TDD Red：Chat 外部注入测试 — 验证从外部传入 skillId + 上下文后，ChatViewModel 正确切换 skill 并发送首条消息。
5) TDD Green：实现字幕视图长按 → Chat 跳转 → skill 切换 → 上下文注入 → 自动回复。
6) TDD Red：`TtsClient` mock 测试 — synthesize 正常/超时/失败。
7) TDD Red：`BilingualTtsWorker` 单测 — interleaved 模式拼接逻辑（原文 + 静音 + 译文 + 间隔）；target_only 模式拼接逻辑。
8) TDD Green：实现 `BilingualTtsTaskManager` + `BilingualTtsWorker` + CLI `radio tts`。
9) Verify：UT 全绿；真机冒烟（长按 → Agent 回复；TTS 产出可播放音频）。

## Risks

- TTS 选型：OpenAI TTS 质量好但有网络/费用依赖；Android 系统 TTS 免费离线但语音质量参差不齐（尤其日语）。建议 Analysis 阶段在 Nova 9 上实测系统 TTS 的日语/中文质量，再做决策。
- 音频拼接复杂度：多段 TTS 音频 + 静音段拼接为单个 OGG 文件，需要处理采样率/声道数一致性。如果 TTS 输出格式不统一，可能需要先统一解码为 PCM 再重新编码。
- Chat 外部注入的侵入性：ChatViewModel 需要支持"从外部传入 skill + 上下文并自动发送"，需确认不破坏现有 Chat 交互流程。
- language-tutor 回复质量：依赖 LLM 的语言学知识，日语语法分析的准确性需要人工抽检。v43 先保证"可用"，质量调优后续迭代。