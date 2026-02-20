# PRD-0034：Radio 录制、转录翻译与实时翻译管线

日期：2026-02-19  
作者：柠檬叔 + Kiro  
工程：kotlin-agent-app（Android）  
状态：草案（Draft）  

> 编号说明：仓库内 `PRD-0031` 已用于 `exchange-rate-cli`，本需求文档落位为 `PRD-0034`（不占用既有编号）。
>
> 关联文档：
> - 既有 Radio 播放：`docs/prd/PRD-0030-radio-player.md`
> - Radio 模块总览：`docs/plan/v38-radio-module-overview.md`
> - 详细草案（来源/原始讨论稿）：`research/big_feat_radio.md`

## Vision

在 Radio 模块（v38）“电台即文件”的基础上，让用户在 **Files（VFS）** 中获得从“能录”到“能边听边翻译”的逐层交付价值，并保持：

- 录制 / 转录 / 翻译 / TTS / 实时字幕等产物 **全部落盘为文件**（Everything is FileSystem）
- 后台可恢复、失败可解释、可审计（`terminal_exec` 白名单命令）
- 安全边界清晰：只对 `workspace/radios/**.radio` 启用能力；产物只写入 App 内部 `.agents/workspace/**`

## Non-Goals（本 PRD 明确不做）

- 不做本地 ASR 模型（仅云端；v40 默认实现为 DashScope Qwen-ASR 录音文件异步转写，后续可接入 Whisper/火山等）
- 不允许录制任意 URL（只能录制 `radios/` 子树内 `.radio` 指向的受控来源）
- 不追求“真流式” ASR（Whisper 不支持流式输入；采用 5–10 秒 buffer 的伪流式策略）

## 核心设计决策（锁定口径）

| 决策点 | 结论 |
|---|---|
| 录制方案 | Media3 独立 Player 实例解码为 PCM，MediaCodec 编码为 Opus + MediaMuxer OGG 写盘（API 29+） |
| 并发录制上限 | 最多 2 路 |
| ASR 后端 | 仅云端（v40 首个实现为 DashScope Qwen-ASR 文件异步转写；通过接口抽象可扩展 Whisper/火山等） |
| 实时延迟目标 | 可接受 ≤ 10 秒（buffer 5–10 秒） |
| 落盘音频格式 | 统一 OGG Opus（`.ogg`），64kbps |
| 译文播放模式（实时） | 双模式：①仅译文语音 ②原声（降音量）+ 译文交替 |

## 分层与交付版本

```
Layer 4: 实时翻译管线（Live Translation Pipeline）     — v45, v46
Layer 3b: 语言学习交互（language-tutor + TTS）          — v43
Layer 3a: 双语学习播放器（Session Player）              — v42
Layer 2: 离线转录 / 翻译（Transcript & Translation）    — v40, v41
Layer 1: 后台录制（Radio Recording）                    — v39
Layer 0: 既有 Radio 模块（已完成）                      — v38
```

## Workspace（落盘约定，最小稳定口径）

录制根目录（真实落盘）：

```
.agents/workspace/radio_recordings/
  _STATUS.md
  .recordings.index.json
  {session_id}/
    _meta.json
    _STATUS.md
    chunk_001.ogg
    ...
    transcripts/                    # v41 起：平铺（无 tx_*/_task.json）
      chunk_001.transcript.json
      chunk_002.transcript.json
      ...
    translations/                   # v41 起：平铺
      chunk_001.translation.json
      chunk_002.translation.json
      ...
    audio_bilingual/                # v43（可选）
      _task.json
      chunk_001_bilingual.ogg
      ...
  live_*/                           # v46（可选，live 会话）
    _meta.json
    _STATUS.md
    ...
```

> 说明（[已由 ECN-0007 变更]）：v40 版本曾采用 `transcripts/_tasks.index.json + tx_*/_task.json` 的多任务落盘模型；v41 起按 plan 简化为“一个 session 一条 pipeline”，转录与翻译结果平铺在 `transcripts/` 与 `translations/` 下（不再有 `tx_*/` task 子目录）。

> 具体 schema 与字段细节以 `research/big_feat_radio.md` 为准；本 PRD 的稳定锚点是“目录结构 + schema 名称 + 可验收行为”。

## Command Surface（受控输入 + 稳定输出）

全部能力通过 `terminal_exec` 的白名单命令 `radio` 暴露（新增子命令）：

- `radio record ...`（v39）
- `radio transcript ...`（v40）
- `radio tts ...`（v43）
- `radio live ...`（v45 / v46）

要求：

- help 必须可用：`radio --help` / `radio help` / `radio <sub> --help` 均 `exit_code=0`
- 输入门禁：只允许 `workspace/radios/**.radio` 路径（或等价的受控引用）
- 输出契约与现有 `radio` CLI 一致：`exit_code/stdout/result/artifacts/error_code/error_message`

## Requirements（Req IDs + 可二元验收）

### General（跨版本）

- **REQ-0034-000（VFS 根目录）**：Files 根目录必须出现 `radio_recordings/`（对应 `.agents/workspace/radio_recordings/`），目录不存在时 App 自动创建；失败必须可解释（`_STATUS.md`）。  
- **REQ-0034-001（Everything is FileSystem）**：录制/转录/翻译/TTS/实时落盘产物必须可在 `radio_recordings/` 下以文件形式浏览、导出、清空（清空需二次确认）。  
- **REQ-0034-002（受控输入）**：所有会触发网络/音频采集/录制的操作，仅允许从 `workspace/radios/**.radio` 或当前正在播放的 station 派生；不得支持任意 URL。  

验收（建议自动化 + 真机证据）：
- 单测：路径门禁 + 输出字段稳定。
- 真机：打开 Files 可见目录；进入 `_STATUS.md` 可解释。

### v39（Layer 1：后台录制）

- **REQ-0034-010（录制会话与并发上限）**：支持开始/停止录制；最多并发 2 路，超限必须返回 `MaxConcurrentRecordings`（或等价 error_code）。  
- **REQ-0034-011（切片与格式）**：录制产物按 10 分钟切片写入 `.ogg`（Opus 64kbps，OGG 封装，API 29+）；每片必须可独立播放。  
- **REQ-0034-012（元信息与索引）**：每会话写入 `_meta.json` 与会话级 `_STATUS.md`；根索引 `.recordings.index.json` 为 pretty JSON（多行）。  
- **REQ-0034-013（后台可靠性）**：切页签/锁屏/后台不中断（前台服务 + 通知栏状态）。  
- **REQ-0034-014（CLI：radio record）**：新增 `radio record start|stop|status|list`（最小闭环可调整，但必须可审计、可测试、可解释）。  
- **REQ-0034-015（录音回放）**（[已由 ECN-0005 变更]）：在 Files 进入 `radio_recordings/` 后，点击任意 `chunk_*.ogg` 必须可触发 App 内播放器直接播放（仅允许 `workspace/radio_recordings/**.ogg` 子树）。  

### v40（Layer 2a：离线转录）

- **REQ-0034-050（任务模型与落盘）**：在会话目录创建 `transcripts/`，并以 `_tasks.index.json` + `{task_id}/_task.json` 表达状态；每 chunk 输出 `chunk_NNN.transcript.json`（含时间戳 segments）。  
- **REQ-0034-051（后台慢任务）**：转录为后台慢任务（WorkManager）；App 被杀后可恢复；支持取消。  
- **REQ-0034-052（云端 ASR：DashScope Qwen-ASR）**：调用云端 ASR（v40 默认：阿里云百炼 DashScope 的 Qwen-ASR 录音文件异步转写，上传 `.ogg`/Opus 获取 `oss://` 临时 URL → 提交异步任务 → 轮询结果）；并将错误归一为稳定 `error_code`（AsrNetworkError/AsrRemoteError/AsrParseError/AsrUploadError/AsrTaskTimeout/…）。  
- **REQ-0034-053（CLI：radio transcript）**：新增 `radio transcript start|status|list|cancel`（受控输入）。  
  - UI：在 Files 的 `radio_recordings/` 下长按录制会话目录，可触发“开始转录/重新转录（覆盖确认）”，对仍在录制中的会话必须拒绝并可解释。  

### v41（Layer 2b：离线翻译）

- **REQ-0034-080（翻译落盘 + 目录结构简化）**：翻译结果必须落盘为 `translations/chunk_NNN.translation.json`；转录结果落盘为 `transcripts/chunk_NNN.transcript.json`；translation segments 必须与 transcript segments 时间戳对齐（同 id/start/end）。  
- **REQ-0034-081（录完自动管线 + 可恢复进度）**：当录制会话结束（`state=completed`）后，若该会话配置了目标语言，则自动触发离线串行管线：转录 → 翻译（无需手动干预；可断点续跑、失败可解释）；pipeline 状态与进度计数必须以 `_meta.json`（或等价稳定文件）表达。  
  - 说明：**不做“边录边转”**（录制中不触发任何处理）；“新 chunk 产出即进入转录/翻译队列”的实时/准实时能力归入 v45/v46 的 Live Translation Pipeline。  

### v42（Layer 3a：双语学习播放器） （[已由 ECN-0007 变更]）

- **REQ-0034-100（双语播放入口与全屏播放器）**：在 Files 的 `radio_recordings/` 下长按录制 session 目录，必须提供“🎧 双语播放”入口进入全屏播放器；若 session 内无 `chunk_*.ogg`，入口灰显并提示“无录音文件”。  
- **REQ-0034-101（多 chunk 顺序播放 + 播放控制）**：播放器必须按文件名顺序连续播放 session 内所有 `chunk_NNN.ogg`（chunk 间自动衔接）；支持跨 chunk 的总进度 seek；支持变速（0.5x/0.75x/1.0x/1.25x/1.5x/2.0x）；支持“上一句/下一句”跳转（按字幕 segment 时间戳）。  
- **REQ-0034-102（双语字幕同步 + 高亮滚动 + 点击定位）**：字幕区必须随播放位置高亮当前 segment 并自动滚动；点击任意 segment 必须 seek 到该句 start 并播放；字幕数据优先读取 `translations/chunk_NNN.translation.json`，缺失时降级读取 `transcripts/chunk_NNN.transcript.json`，都缺失则显示“暂无字幕”，且不阻塞播放。  

### v43（Layer 3b：language-tutor Agent + TTS 双语听力） （[已由 ECN-0007 变更]）

- **REQ-0034-103（语言学习 Agent）**：内置 `language-tutor` 技能（`SKILL.md`），长按/选中字幕 segment 可触发“学习”交互并向 agent 提供上下文（选中句 + 周边句 + 语言对 + 用户水平）；最小实现允许复用 Chat 页签承载面板（跳转并自动切换 skill）。  
- **REQ-0034-104（TTS 双语听力 + CLI）**：支持生成双语听力音频产物 `audio_bilingual/`（至少支持 `interleaved` / `target_only` 两种模式），并提供 `radio tts start|status|cancel` 可审计触发与查询；任务进度以 `_task.json`（或等价稳定文件）表达，失败必须可解释。  

### v44（基础设施：ASR/TTS 模块化 + 通道隔离）

- **REQ-0034-130（AsrService 接口）**：抽象 `AsrService.transcribeFile` 与 `AsrService.transcribeStream`；Radio/Chat 共享实现但并发隔离、限流。  
- **REQ-0034-131（TtsService 接口）**：抽象 `TtsService.synthesize` 与（可选）`synthesizeStream`；provider 可切换；Settings 可配置默认 voice。  
- **REQ-0034-132（与 Chat 并发口径）**：Chat 语音输入/输出优先级高于 Radio 管线；并发与 AudioFocus 策略必须可解释且不互相打断到不可用。  

### v45（Layer 4：实时翻译管线 + MixController） （[已由 ECN-0007 变更]）

- **REQ-0034-180（实时翻译管线 + 三模式混音）**：支持实时翻译闭环：电台音频 → ASR → 翻译 →（可选）TTS → 混音播放；支持三种模式：交替（原声 duck + 译文朗读）/ 仅译文（原声静音 + 译文朗读）/ 仅字幕（不播 TTS）；并提供 `radio live start|stop|status` 最小 CLI 闭环（受控输入、稳定输出、可审计）。  
- **REQ-0034-181（实时字幕追加模式）**：字幕视图支持 streaming 追加（新 segment 实时追加到底部并可自动滚动），并展示译文延迟指示；`subtitle_only` 模式下不影响原声播放且不产出 TTS 播放副作用。  

### v46（持久化 + AudioFocusManager + `radio live` 扩展） （[已由 ECN-0007 变更]）

- **REQ-0034-182（全链路落盘 + 音频焦点仲裁）**：live 会话支持可选的全链路落盘（原声切片、ASR/翻译 JSONL、TTS 音频等）到 `workspace/radio_recordings/live_*/`；并引入 `AudioFocusManager` 统一仲裁 app 内音频源优先级（至少：`CHAT_TTS > RADIO_TTS > RADIO_PLAYBACK`）且能接收并分发系统 AudioFocus 事件；`radio live` CLI 提供落盘开关参数并写入 `_meta.json` 可审计。  

## Open Questions（进入 v39 实施前需确认的口径）

1) `radio record/transcript/tts/live` 是否全部挂在既有 `RadioCommand.kt` 内，还是在 v39 先进行一次 CLI 结构重构（避免文件继续膨胀）。  
2) 实时翻译落盘默认值：默认不落盘？（隐私与存储压力）；需要 Settings 总开关吗？  
