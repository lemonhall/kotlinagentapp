# v48：Radio Live 同声传译（仅译音）

日期：2026-03-10  
工程：kotlin-agent-app（Android）  
状态：Draft → Implement

## 背景与目标

现有 v47 已在 Files 下落地独立 app：`simultaneous_interpretation/`（同声传译），主要面向“麦克风输入 + 耳机输出”的连续同传场景。

本 v48 目标是把同传底座复用到 **radio**：

- 当正在播放某个电台频道（`radios/**.radio`）时，播放器 UI 增加 **Live** 按钮；
- 点击 Live 后，电台进入“同传译音模式”：
  - 不展示翻译文本；
  - 不落盘归档；
  - 只播放目标语言语音（model audio stream）。
- 再次点击 Stop Live：停止同传，恢复原电台正常播放/可听。

## 产品交互（DoD）

### 入口与状态

- 仅当当前 NowPlaying 为 `.radio`（且在 `radios/` 子树）时显示 Live。
- 点击 Live：
  - 弹出目标语言选择器（复用 `TranslationLanguagePickerDialog`）；
  - 选定后进入 Live 模式，Live 按钮变为 Stop Live；
  - 原电台不再外放（不再“可听”），只输出译音。
- 点击 Stop Live：
  - 停止同传会话；
  - 恢复电台正常播放（继续听原频道）。

### 关键说明

- radio Live 同传的音频输入源为 **电台流解码后的 PCM**，不经过麦克风，因此不存在“外放回授/识别自己”的问题。

## 技术方案（推荐路线 #1）

### 总体架构

```
UI (Radio Player Sheet)
  -> RadioLiveSimintController (start/stop, state, error)
    -> RadioPlaybackPcmTapInputSource (ExoPlayer + TeeAudioProcessor)
      -> Resample/downmix to 16k/mono/pcm16
    -> DashScopeAliyunLiveTranslateClient (OmniRealtimeConversation / WS)
    -> AudioTrackLiveTranslateAudioPlayer (play model audio delta)
```

### 输入源：RadioPlaybackPcmTapInputSource

- 新建一个 ExoPlayer 实例播放同一电台 URL，用于解码拿到 PCM：
  - 使用 `TeeAudioProcessor(AudioBufferSink)` 获取 PCM ByteBuffer；
  - `DefaultAudioSink.Builder(...).setEnableFloatOutput(false)`，确保 PCM16。
- 对 PCM 做：
  - `downmix` 到 mono；
  - 重采样到 16k；
  - 按 20ms（`320 samples = 640 bytes`）切帧回调给同传 WS `appendAudioFrame(...)`。

### Live 会话：仅消费 audio delta

- 复用 v47 的 WS client：`DashScopeAliyunLiveTranslateClient`（model：`qwen3-livetranslate-flash-realtime`）。
- UI 侧不展示文本，但协议侧仍可开启 `input_audio_transcription` 用于 turn detection 与 response 驱动；文本事件忽略即可。

## 边界与非目标

- 不改动既有电台录制/离线转录/离线翻译链路。
- 不实现“回放/回溯目录”。
- 不实现“原声 + 译音混音”。

## 验证建议

- 单测：新增 Robolectric/UT 覆盖 controller start/stop 状态机（mock client/input source/player）。
- 真机：`.\gradlew.bat :app:installDebug`，播放电台 → Live → 选择 `zh`，确认听到中文译音；Stop Live 后恢复原电台可听。

