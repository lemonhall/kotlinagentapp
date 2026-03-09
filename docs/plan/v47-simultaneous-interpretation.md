# v47：Files 同声传译（Qwen3 LiveTranslate Realtime）

日期：2026-03-10  
作者：柠檬叔 + Codex  
工程：kotlin-agent-app（Android）  
状态：设计草案（Draft）  

## Vision

在 **Files 页签** 下新增一个独立 app：`simultaneous_interpretation`（展示名：`simultaneous_interpretation（同声传译）`），面向“连续听几分钟到十几分钟”的场景，使用阿里云百炼 `qwen3-livetranslate-flash-realtime` 实现：

- 麦克风连续输入源语音；
- 实时返回目标语言文本；
- 实时播放目标语言语音；
- 全部会话产物落盘到 `.agents/workspace/simultaneous_interpretation/`；
- 保持现有 `instant_translation`（即时翻译）能力**完全不受影响**。

该能力与现有“即时翻译”的定位明确区分：

- `instant_translation`：旅行中、对话中、短句级的“说一句、译一句、播一句”；
- `simultaneous_interpretation`：耳机佩戴场景下、连续讲话或连续听讲的“低延迟连续翻译”。

## Why Separate App

本项目已经有 `instant_translation`，而且用户已经明确认可其产品定位与交互方式，因此本轮不在原链路上继续堆能力，而是单开新 app，原因如下：

1. **产品定位不同**：即时翻译偏短句轮转；同声传译偏连续会话。
2. **技术形态不同**：即时翻译是“ASR → 翻译 → TTS”的片段式管线；同声传译是单一实时 WebSocket 会话，持续输入音频并持续接收文本/语音输出。
3. **风险隔离**：新能力的延迟、音频路由、耳机依赖、归档格式都与旧功能不同；拆分后不会污染已稳定的即时翻译体验。
4. **复用价值更高**：若设计成独立底座，未来可复用于 radio 同传，而不是把 radio 逻辑反向塞回即时翻译页面。

## Official Model Notes（2026-03-10）

根据阿里云百炼官方文档，`qwen3-livetranslate-flash-realtime` 具备以下与本需求直接相关的能力：

- 通过 **WebSocket** 接入；中国内地地址为：`wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-livetranslate-flash-realtime`
- 支持 `session.modalities = ["text"]` 或 `["text", "audio"]`
- 目标语种通过 `session.translation.language` 配置
- 如果配置 `session.input_audio_transcription.model = qwen3-asr-flash-realtime`，则服务端还会返回源语言识别结果
- 服务端会通过 `response.audio.delta` 返回增量音频，通过 `response.audio_transcript.done` 返回完整译文文本
- 官方文档强调其面向低延迟同传场景，延迟目标可低至约 3 秒

外部参考：

- 实时音视频翻译-千问：<https://help.aliyun.com/zh/model-studio/qwen3-livetranslate-flash-realtime>
- 支持的模型列表与价格：<https://help.aliyun.com/document_detail/2840914.html>

## Scope（v47）

### In Scope

- 在 Files 根目录新增 `simultaneous_interpretation/` app 目录
- 长按目录可显示“进入目录 / 打开同声传译 / 复制路径 / 删除”等动作
- 新增 `SimultaneousInterpretationActivity`
- 麦克风实时采集 PCM 音频并发送到 `qwen3-livetranslate-flash-realtime`
- 页面中显示：
  - 当前连接/录音状态
  - 源语言（可选返回）
  - 目标语言译文
  - 当前会话错误
- 播放模型返回的目标语言音频
- 将输入音频、返回文本、返回音频、关键事件落盘到工作区
- 目标语言可选，默认英文
- 对“未佩戴耳机/当前走扬声器”做明确提示

### Out of Scope（v47 明确不做）

- 不修改现有 `instant_translation` 的数据结构、页面交互与归档逻辑
- 不实现 radio 音频输入
- 不实现 SMB 视频 / 电影音轨输入
- 不实现“原声 + 译音混音”播放
- 不实现后台常驻 Service 级长时任务；v47 先以 Activity 前台使用为准
- 不实现图片/视频帧视觉增强输入；v47 先只做音频输入

## Product Decisions

### 1. 耳机优先，而不是扬声器优先

同声传译与即时翻译最大的现实区别，不在模型，而在**音频回授风险**。

- 即时翻译可以播放一句就停，并且我们已经在播放时暂停 ASR，避免“识别自己”；
- 同声传译需要持续采集麦克风，同时持续播放译音；如果外放，很容易把 TTS/译音重新收进麦克风，形成回授污染。

因此 v47 的产品口径锁定为：

- **推荐并优先支持耳机场景**；
- 若检测到当前输出路由为手机扬声器，则弹出提示；
- 首版不强制禁止扬声器，但会明确提示“外放可能导致回授、影响翻译效果”。

### 2. 目标语言可选，默认英文

虽然当前脑洞首先是“中文 -> 英文”，但从产品资产角度，既然底座本身就是语言无关的，则应在 v47 直接做成：

- 目标语言可选；
- 默认 `en`；
- UI 上保留语言选择器；
- 归档元数据必须写入 `sourceLanguageHint` 与 `targetLanguageCode/Label`。

### 3. 源语言文本尽量一起拿

即使主目标是“实时听到译音”，源语言文字也很有价值：

- 便于用户确认自己是否说对；
- 便于事后回溯；
- 为未来 radio 同传复用提供统一文本轨。

因此 v47 推荐开启：

- `session.input_audio_transcription.model = qwen3-asr-flash-realtime`
- 并接收：
  - `conversation.item.input_audio_transcription.text`
  - `conversation.item.input_audio_transcription.completed`

## Files / Workspace Layout

```text
.agents/workspace/simultaneous_interpretation/
  _STATUS.md
  sessions/
    2026年03月10日 晚22点01分/
      meta.json
      events.jsonl
      input_audio.pcm
      translated_audio.wav
      segments.jsonl
      source.md
      translation.md
```

### 文件说明

- `_STATUS.md`
  - 当前功能说明、使用建议、耳机提醒
- `meta.json`
  - 会话级元数据：开始时间、结束时间、源语种 hint、目标语种、模型名、音色、采样率、状态、错误信息
- `events.jsonl`
  - 原始事件摘要（非完整服务器包转储；仅保留排障必要字段）
- `input_audio.pcm`
  - 麦克风原始输入音频，便于将来离线回放/复盘
- `translated_audio.wav`
  - 本次同传输出的完整目标语言音频轨
- `segments.jsonl`
  - 按“翻译完成片段”或“最终识别片段”记录时间线
- `source.md`
  - 聚合后的源语言文本
- `translation.md`
  - 聚合后的目标语言文本

## Runtime Architecture

```text
UI(Activity / Compose)
  -> SimultaneousInterpretationViewModel
    -> LiveTranslateSessionController
      -> LiveTranslateAudioInputSource (Mic v47 / Radio future)
      -> AliyunLiveTranslateClient (WebSocket)
      -> LiveTranslateAudioPlayer
      -> SimultaneousInterpretationArchiveManager
```

### 关键边界

#### `LiveTranslateAudioInputSource`

职责：只负责“产出 PCM 音频帧”。

v47 先实现：
- `MicrophoneLiveTranslateAudioInputSource`

未来可扩展：
- `RadioPlaybackPcmTapInputSource`
- `VideoAudioTrackInputSource`

这是本轮最重要的复用点：**radio 同传未来不是重写 UI，而是更换输入源。**

#### `AliyunLiveTranslateClient`

职责：
- 建立 WebSocket 连接
- 发送 `session.update`
- 连续发送 `input_audio_buffer.append`
- 解析服务端事件
- 向上抛出统一事件流：
  - `OnSourceTranscriptPartial`
  - `OnSourceTranscriptFinal`
  - `OnTranslationTextFinal`
  - `OnTranslationAudioDelta`
  - `OnUsage`
  - `OnError`

实现建议：
- 首版直接使用 **OkHttp WebSocket**，按官方协议自己做事件编解码
- 理由：协议透明、易于调试、未来接 radio / video 输入时可控性更强

#### `LiveTranslateAudioPlayer`

职责：
- 播放 `response.audio.delta` 的 PCM 增量音频
- 同时将 PCM 汇总写为 `translated_audio.wav`

说明：
- 该播放器与即时翻译里 `qwen3-tts-flash-realtime` 的播放器可在 PCM/WAV 工具层复用
- 但会话控制逻辑应保持独立

## UI / UX（v47）

页面建议保持“简洁、连续、可长时间盯看”的风格。

### 顶部

- 返回
- 标题：`同声传译`
- 目标语言选择器

### 状态卡片

显示：
- 连接状态：未连接 / 连接中 / 运行中 / 错误
- 音频路由：耳机 / 蓝牙 / 扬声器
- 提示文案：若当前非耳机，提示可能出现回授

### 主体区域

分上下两块：

1. 源语言区域
   - “识别中” partial 文本
   - 已确认 final 文本滚动列表

2. 目标语言区域
   - 已完成译文滚动列表
   - 不额外显示“播放按钮”，因为它本身就是连续播出的

### 底部操作区

- `开始同传`
- `停止同传`
- `打开归档目录`

## Event Mapping（协议级）

### Client -> Server

- `session.update`
- `input_audio_buffer.append`
- （必要时）会话结束事件

### Server -> Client

- `conversation.item.input_audio_transcription.text`
- `conversation.item.input_audio_transcription.completed`
- `response.audio.delta`
- `response.audio_transcript.done`
- `response.done`
- 错误事件 / close 事件

## Error Handling

### 用户侧错误

- 未授予麦克风权限
- 未配置 `DASHSCOPE_API_KEY`
- 当前走扬声器，存在回授风险

### 网络 / 服务侧错误

- WebSocket 连接失败
- 中途断线
- 服务端返回错误包
- 服务端长时间无活动（空闲超时）

### 行为原则

- 所有错误都必须进入 UI 可见状态
- 所有错误都必须写入 `meta.json` 与 `_STATUS.md` 或 `events.jsonl`
- 会话结束时要保证归档文件闭合，不留下损坏的 WAV 头或空 JSON

## Why This Helps Radio Later

未来 radio 同传要解决的问题是：

- 输入音频不再来自麦克风，而来自 radio 播放 PCM
- 用户希望 5-6 秒延迟内听到中文译音
- 需要持续很久地稳定运行

如果 v47 按本方案落地，则 radio 同传只需要新增：

- 一个把 radio PCM 喂给 `AliyunLiveTranslateClient` 的输入源
- 一个更适合 radio 的会话 UI / 播放策略
- 一个更细化的延迟与断线恢复策略

也就是说，**同传的核心底座（会话协议、事件流、归档格式、播放器、状态机）可以直接复用。**

## Movie / SMB Video Scenario（只记录，不纳入 v47）

当前先不实现，但这里提前记下约束，避免以后返工：

- 若未来处理 SMB 英文电影，只播译音会丢失原片音乐和声效
- 因此视频场景不能简单复用“只播译音”的策略
- 真正可接受的方案应是：
  - 原声保留（降音量）
  - 译音插入 / 交替 / ducking
  - 或者只提供字幕与“按需朗读”模式

这说明电影场景虽然能复用同传协议底座，但**音频混音策略必须单独设计**。

## Testing Strategy

### Automated

- `DashboardSimultaneousInterpretationRulesTest`
  - Files 根目录识别规则
  - 长按动作列表
- `SimultaneousInterpretationViewModelTest`
  - 开始 / 停止 / 错误态 / 语言切换
- `AliyunLiveTranslateClientTest`
  - 事件解析与状态转移
- `SimultaneousInterpretationArchiveManagerTest`
  - 会话目录、`meta.json`、`segments.jsonl`、WAV 输出

### Manual

- 戴耳机启动同传，中文讲话，确认 3-6 秒级延迟内听到英文译音
- 切到扬声器，确认会出现风险提示
- 结束后在 Files 中可进入归档目录，看到文本与音频文件

## Milestones

### M1：文档与入口

- `docs/plan/v47-*` 文档齐备
- Files 根目录出现 `simultaneous_interpretation`
- 可打开独立 Activity 空页面

### M2：协议跑通

- WebSocket 建连成功
- 可持续送麦克风 PCM
- 能收到译文文本与译音增量

### M3：落盘与回看

- 会话目录落盘
- `source.md` / `translation.md` / `translated_audio.wav` 可用
- Files 中可回到会话目录查看

### M4：体验收口

- 耳机提示
- 错误文案优化
- 超时 / 断线收尾完善

## Summary

v47 的核心不是“再做一个翻译页面”，而是：

- 在 Files 里新增一个**长时、连续、耳机导向**的同传 app；
- 用 `qwen3-livetranslate-flash-realtime` 跑通“连续输入 -> 连续译文文本/语音输出”；
- 把底座抽成“输入源 / 实时翻译会话 / 播放器 / 归档”四层；
- 为后续 `radio` 同传留下最自然的复用路径。
