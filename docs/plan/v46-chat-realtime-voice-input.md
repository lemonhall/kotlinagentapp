# v46：Chat 实时语音输入（Fun-ASR Realtime）

## Goal

- 在首页聊天输入框右侧新增录音按钮，点击后使用麦克风实时采集语音，并通过阿里云百炼 `fun-asr-realtime` 做流式识别，将识别文本实时回填到聊天输入草稿框。
- 保持这套能力与现有“录音文件离线转写 / 翻译 / 双语播放”管线彻底分离，避免职责混杂。
- 为未来在其它页面复用实时语音输入能力预留稳定接口和最小耦合边界。

## Scope

### In

- Chat 首页输入框右侧麦克风按钮。
- 首次点击时申请 `RECORD_AUDIO` 权限。
- 录音中再次点击按钮可停止实时语音输入。
- Fun-ASR Realtime 接入（北京地域 WebSocket）。
- Settings 中新增 `Voice Input` 配置区，仅暴露 `DASHSCOPE_API_KEY`。
- Key 的读取优先级：Settings 已保存值 > 根目录 `.env` 注入值。
- 独立的 `voiceinput/` 包：配置仓库、草稿拼接器、控制器、实时引擎。

### Out

- 热词配置 UI。
- 语种切换 UI。
- 重连重试策略 UI。
- 与离线录音转写流水线的合并抽象。
- 在其它页面直接复用的最终产品化接入（本轮只把复用接口设计出来）。

## UX / Behavior

1. 用户进入首页聊天页。
2. 输入框右侧展示麦克风按钮。
3. 用户首次点击麦克风：
   - 若未授予麦克风权限，则先申请权限；
   - 若未配置 `DASHSCOPE_API_KEY`，则展示错误提示；
   - 若配置完整，则开始实时语音输入。
4. 录音期间：
   - 输入框展示实时识别草稿；
   - 按钮切换为停止态；
   - 页面展示“准备中 / 识别中 / 错误”状态文案。
5. 用户再次点击按钮后停止录音，当前已确认文本保留在草稿框，用户可以继续手改或直接发送。

## Architecture

### 分层

```text
ChatScreen
  -> ChatFragment
    -> VoiceInputController
      -> VoiceInputEngine
        -> FunAsrRealtimeVoiceInputEngine
          -> AudioRecord + DashScope Recognition(WebSocket)
```

### 核心模块

#### 1) `ChatScreen`

职责：
- 展示输入框、麦克风按钮、发送按钮、语音状态提示。
- 不直接持有语音识别实现；只消费 `voiceInputState` 与回调。

关键点：
- 输入框值改为读取 `ChatUiState.draftText`，而不是页面内局部状态。
- 录音中禁用手工输入与发送，避免用户键入和实时转写同时写草稿造成竞争覆盖。

#### 2) `ChatViewModel`

职责：
- 将聊天草稿提升为页面级状态：`draftText`。
- 提供 `setDraftText()` 和 `sendDraftMessage()`，让手输和语音输入共享同一草稿通道。

这样做的价值：
- 语音输入不会绕开既有发送逻辑；
- 未来其它入口（例如浮动语音按钮、别的页面、可穿戴设备输入）也能直接复用草稿状态。

#### 3) `ChatFragment`

职责：
- 作为 Android 宿主层，处理权限申请、生命周期、实时语音控制器实例化。
- 在 `onStop()` 主动停止语音输入，避免 Fragment 不可见后继续占用麦克风。

它是 UI 与 Android 能力（权限、Context、生命周期）之间的桥接层。

#### 4) `voiceinput/` 独立包

##### `SharedPreferencesVoiceInputConfigRepository`
- 负责读取 / 保存语音输入配置。
- 当前只管理 `DASHSCOPE_API_KEY`，但接口已经以 `VoiceInputConfig` 暴露，后续可继续扩展模型、语言、热词等。

##### `VoiceInputDraftComposer`
- 负责把“已有草稿 + partial / final transcript”拼接成新的草稿。
- 区分 `committedText` 与 `previewText`：
  - partial 只更新预览，不改变最终确认文本；
  - final 才真正提交到 committed 状态。
- 目前做了最小英文空格处理，避免 `open` + `settings` 被拼成 `opensettings`。

##### `VoiceInputController`
- 负责驱动实时语音输入状态机。
- 维护 `VoiceInputUiState`（`isStarting` / `isRecording` / `errorMessage`）。
- 管理 `VoiceInputEngine` 生命周期，向上游输出草稿更新。
- 通过 `engineFactory` 注入具体引擎，解耦 UI 与厂商 SDK。

##### `VoiceInputEngine`
- 抽象出统一实时语音引擎接口：`start(listener)` / `stop()`。
- 本轮仅提供 `FunAsrRealtimeVoiceInputEngine` 实现。
- 未来如需接别家 ASR，只要新增另一个 engine 实现即可，无需改 Chat UI。

## Fun-ASR Realtime Engine Design

### 选择 SDK 路线

本轮采用阿里云 DashScope Java SDK 的 `Recognition` 流式接口，而不是自己手写原始 WebSocket 协议，原因：

- 直接使用官方参数对象与回调模型，接入更稳。
- 未来升级模型版本时改动集中在配置层。
- Android 侧只需专注麦克风采集与生命周期管理。

### 采集策略

- `AudioRecord`
- `VOICE_RECOGNITION` 音源
- `16kHz`
- `PCM 16-bit`
- 单声道

这与 `fun-asr-realtime` 的常规接入参数保持一致。

### 回调策略

- partial transcript -> 更新草稿预览。
- final transcript -> 提交草稿确认文本。
- error -> 终止录音并回传错误信息。
- complete / stop -> 更新控制器状态为非录音中。

### 清理策略

停止时必须同时清理：
- `AudioRecord.stop/release`
- DashScope `recognizer.stop()`
- WebSocket 关闭
- 读音频协程取消

这样可避免：
- 麦克风被系统长时间占用；
- Fragment 不可见后仍在后台采集；
- WebSocket 残留连接导致后续再次启动失败。

## Configuration Strategy

### Settings 层

在 `Settings` 页新增 `Voice Input` 分区，只提供一个输入项：
- `DASHSCOPE_API_KEY`

这样做的原因：
- 这轮真实需求里只有一个配置项；
- 减少 UI 复杂度，避免把模型、URL、采样率等内部实现细节过早暴露给用户。

### `.env` 回退

Debug 构建阶段，根目录 `.env` 中的 `DASHSCOPE_API_KEY` 已通过 `BuildConfig.DEFAULT_DASHSCOPE_API_KEY` 注入。

运行时读取优先级：

```text
SharedPreferences(asr.dashscope_api_key)
  > BuildConfig.DEFAULT_DASHSCOPE_API_KEY
  > blank
```

收益：
- 开发阶段开箱即用；
- 用户仍可在 Settings 中覆盖默认值；
- 不依赖离线转写使用的工作区 `.env` 文件，保持边界清晰。

## 与离线 ASR 的边界

本轮明确不复用仓库中已有的离线转写设施，理由：

- 离线转写的输入是“已存在的录音文件”；实时输入的输入是“麦克风音频流”。
- 离线转写重点在任务管理、文件上传、轮询结果；实时输入重点在低延迟回填、权限、录音生命周期、WebSocket 长连接。
- 若强行共用实现，会在接口层引入过多条件分支，导致职责混乱。

因此本轮采用“协议 / 接口级复用、业务实现级隔离”的策略：
- 复用已有配置 key 命名（`DASHSCOPE_API_KEY`）；
- 不复用离线转写 manager / task / pipeline 代码。

## Reuse / Extension Points

未来若要把实时语音输入复用到别处，推荐沿用以下边界：

1. 页面只依赖 `VoiceInputController` 与 `VoiceInputUiState`。
2. 控制器只依赖 `VoiceInputEngine` 抽象。
3. 配置统一从 `SharedPreferencesVoiceInputConfigRepository` 获取。
4. 草稿拼接规则统一复用 `VoiceInputDraftComposer`。

这样未来可以很自然地扩展到：
- Dashboard 搜索框语音输入
- Settings 某些文本配置项的语音录入
- 全局悬浮语音输入入口
- 其它 ASR 服务商接入

## Testing / Verification

### Automated

已新增并通过以下测试：

- `VoiceInputDraftComposerTest`
  - partial 不污染 committed 文本
  - final 正确提交草稿
  - 英文单词拼接空格处理
- `SharedPreferencesVoiceInputConfigRepositoryTest`
  - Settings 已保存值优先
  - `.env` 默认值回退生效
- `VoiceInputControllerTest`
  - partial / final transcript 能驱动草稿更新
  - engine 初始化失败时进入错误态
- `ChatViewModelTest`
  - `sendDraftMessage()` 使用当前草稿并在发送后清空输入框

### Commands

- `./gradlew.bat :app:testDebugUnitTest --tests "com.lsl.kotlin_agent_app.voiceinput.*" --tests "com.lsl.kotlin_agent_app.ui.chat.ChatViewModelTest" --tests "com.lsl.kotlin_agent_app.MainActivityChatNavigationTest"`
- `./gradlew.bat :app:installDebug`

### Manual

- 2026-03-09：柠檬叔手工验收通过。

## Current Limitations

- 当前固定使用北京地域 WebSocket 地址，尚未开放国际地域切换。
- 还没有热词、语言 hint、VAD、心跳等高级配置 UI。
- 暂未实现断线自动重连；若网络波动导致连接失败，当前策略是停止并提示错误。
- 录音中禁用了文本框手输，这是本轮为保证草稿一致性做的保守策略；未来如要支持边说边改，需要引入更细粒度的冲突合并策略。

## Future Work

1. 增加热词、语言 hint、模型选择等高级配置。
2. 增加重连与心跳策略，提升长时会话稳定性。
3. 将 `VoiceInputController` 进一步抽成可被多个页面共享的宿主组件。
4. 评估是否需要支持“按住说话”和“自动结束一句后停止”两种交互模式。
5. 在需要时补一份对应 PRD，把本轮已落地能力固化为正式需求资产。
