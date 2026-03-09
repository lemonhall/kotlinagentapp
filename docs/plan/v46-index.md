# v46 Index：Chat 实时语音输入（Fun-ASR Realtime）
日期：2026-03-09

## Vision

- 在主界面聊天输入框右侧新增麦克风按钮，支持“边说边出文字”的实时语音输入。
- 采用阿里云百炼 `fun-asr-realtime`，使用独立的实时语音输入模块，不与仓库内已有离线录音转写设施耦合。
- 在 Settings 中新增 `Voice Input` 配置区，允许填写 `DASHSCOPE_API_KEY`；若用户未填写，则自动回退到根目录 `.env` 注入的 `DASHSCOPE_API_KEY`。
- 设计上抽出可复用的控制层、配置层和引擎层，为未来在别的页面复用实时语音输入能力做准备。

## Plans

- `docs/plan/v46-chat-realtime-voice-input.md`

## Traceability（Req → Plan → Verification）
| Req ID | Plan | Verification（命令 / 证据） |
|---|---|---|
| REQ-0040-010 主界面入口 | `v46-chat-realtime-voice-input` | 手工：主界面输入框右侧可见麦克风按钮，点击可开始/停止语音输入 |
| REQ-0040-020 Fun-ASR 实时识别 | `v46-chat-realtime-voice-input` | 手工：说话时输入框实时出现识别文本；实现基于 DashScope `fun-asr-realtime` |
| REQ-0040-030 与离线 ASR 解耦 | `v46-chat-realtime-voice-input` | 代码结构：新增独立 `voiceinput/` 包，不复用 `radio_transcript/**` |
| REQ-0040-040 Settings + `.env` 配置 | `v46-chat-realtime-voice-input` | 手工：Settings 可编辑 Key；代码：SharedPreferences 优先，`.env` 回退 |
| REQ-0040-050 可复用架构 | `v46-chat-realtime-voice-input` | 单测：`voiceinput/*Test`；代码：`VoiceInputEngine` / `VoiceInputController` / `VoiceInputDraftComposer` |

## Review / Evidence

- 单测：`./gradlew.bat :app:testDebugUnitTest --tests "com.lsl.kotlin_agent_app.voiceinput.*" --tests "com.lsl.kotlin_agent_app.ui.chat.ChatViewModelTest" --tests "com.lsl.kotlin_agent_app.MainActivityChatNavigationTest"`
- 安装：`./gradlew.bat :app:installDebug`
- 人工验收：2026-03-09，柠檬叔已确认主界面实时语音输入功能可用。

## Notes

- 本文档属于“实现完成并人工验收通过后的设计补档”，用于固化方案、接口边界和后续扩展点。
