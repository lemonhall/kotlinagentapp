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

- 不做本地 ASR 模型（仅云端，Whisper API 优先）
- 不允许录制任意 URL（只能录制 `radios/` 子树内 `.radio` 指向的受控来源）
- 不追求“真流式” ASR（Whisper 不支持流式输入；采用 5–10 秒 buffer 的伪流式策略）

## 核心设计决策（锁定口径）

| 决策点 | 结论 |
|---|---|
| 录制方案 | Media3 独立 Player 实例解码为 PCM，MediaCodec 编码为 Opus + MediaMuxer OGG 写盘（API 29+） |
| 并发录制上限 | 最多 2 路 |
| ASR 后端 | 仅云端（OpenAI Whisper API 优先，可扩展其他云端） |
| 实时延迟目标 | 可接受 ≤ 10 秒（buffer 5–10 秒） |
| 落盘音频格式 | 统一 OGG Opus（`.ogg`），64kbps |
| 译文播放模式（实时） | 双模式：①仅译文语音 ②原声（降音量）+ 译文交替 |

## 分层与交付版本

```
Layer 4: 实时翻译管线（Live Translation Pipeline）     — v44, v45
Layer 3: 语言学习交互（Language Learning Agent）        — v42
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
    transcripts/
      _tasks.index.json
      {task_id}/
        _task.json
        _STATUS.md
        chunk_001.transcript.json
        chunk_001.translation.json
        ...
        audio_bilingual/            # v42（可选）
          _task.json
          chunk_001_bilingual.ogg
          ...
```

> 具体 schema 与字段细节以 `research/big_feat_radio.md` 为准；本 PRD 的稳定锚点是“目录结构 + schema 名称 + 可验收行为”。

## Command Surface（受控输入 + 稳定输出）

全部能力通过 `terminal_exec` 的白名单命令 `radio` 暴露（新增子命令）：

- `radio record ...`（v39）
- `radio transcript ...`（v40）
- `radio tts ...`（v42）
- `radio live ...`（v44）

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
- **REQ-0034-052（Whisper API）**：调用云端 ASR（Whisper API 优先），音频上传 `.ogg`（Opus/OGG），并将错误归一为稳定 `error_code`（NetworkError/RemoteError/…）。  
- **REQ-0034-053（CLI：radio transcript）**：新增 `radio transcript start|status|list|cancel`（受控输入）。  

### v41（Layer 2b：离线翻译）

- **REQ-0034-080（翻译落盘）**：可选生成 `chunk_NNN.translation.json`，与 transcript segments 时间戳对齐（同 id/start/end）。  
- **REQ-0034-081（边录边转）**：支持“边录边转”模式：新 chunk 产出后自动提交转录/翻译队列（延迟可接受，失败可解释）。  

### v42（Layer 3：语言学习交互）

- **REQ-0034-100（双语字幕视图）**：点击 `*.translation.json` 不展示原始 JSON，而是渲染双语字幕视图；支持“原文/译文/双语”三种显示模式。  
- **REQ-0034-101（时间定位播放）**：点击任意 segment 时间戳可定位播放对应 chunk 的对应位置；当前播放位置高亮对应 segment。  
- **REQ-0034-102（语言学习 Agent）**：内置 `language-tutor` 技能（`SKILL.md`），长按/选中 segment 可弹出面板并向 agent 提供上下文（选中句 + 周边句 + 语言对 + 用户水平）。  
- **REQ-0034-103（TTS 双语听力）**：支持在转录任务目录生成 `audio_bilingual/`（交替/仅译文两种模式），并以 `_task.json` 表达进度。  
- **REQ-0034-104（CLI：radio tts）**：新增 `radio tts start|status|cancel`。  

### v43（基础设施：ASR/TTS 模块化）

- **REQ-0034-130（AsrService 接口）**：抽象 `AsrService.transcribeFile` 与 `AsrService.transcribeStream`；Radio/Chat 共享实现但并发隔离、限流。  
- **REQ-0034-131（TtsService 接口）**：抽象 `TtsService.synthesize` 与（可选）`synthesizeStream`；provider 可切换；Settings 可配置默认 voice。  
- **REQ-0034-132（与 Chat 并发口径）**：Chat 语音输入/输出优先级高于 Radio 管线；并发与 AudioFocus 策略必须可解释且不互相打断到不可用。  

### v44（Layer 4a：实时翻译 MVP）

- **REQ-0034-150（AudioTee）**：从正在播放的 Radio 流分叉 PCM（ForwardingAudioSink / 等价实现），供 ASR 消费，不影响原声播放稳定性。  
- **REQ-0034-151（伪流式 ASR）**：按 5–10 秒窗口攒 buffer，编码后调用 Whisper，端到端延迟目标 ≤10 秒（允许少量抖动）。  
- **REQ-0034-152（流式翻译字幕）**：ASR segments 经 LLM 翻译后以滚动字幕呈现（原文+译文）；失败时显示“识别中断/正在识别”类可解释状态。  
- **REQ-0034-153（CLI：radio live）**：新增 `radio live start|stop|status`，并支持模式：`interleaved|target_only|subtitle_only`。  

### v45（Layer 4b：实时翻译完整版）

- **REQ-0034-180（TTS 输出）**：实时翻译可输出译文语音，并支持两种播放模式：仅译文 / 原声降音量+译文交替。  
- **REQ-0034-181（全链路可选落盘）**：实时会话可选落盘原始音频切片、ASR/译文 JSONL、TTS 音频片段；落盘后可被离线工具转换为标准 transcript/translation 进入 Layer 2/3。  
- **REQ-0034-182（AudioFocusManager）**：当 Chat TTS 需要播报时，必须优先级高于 Radio TTS（可暂停/恢复 Radio TTS；原声可按策略继续）。  

## Open Questions（进入 v39 实施前需确认的口径）

1) `radio record/transcript/tts/live` 是否全部挂在既有 `RadioCommand.kt` 内，还是在 v39 先进行一次 CLI 结构重构（避免文件继续膨胀）。  
2) 实时翻译落盘默认值：默认不落盘？（隐私与存储压力）；需要 Settings 总开关吗？  
