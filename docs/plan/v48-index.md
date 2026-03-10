# v48 Index：Radio Live 同声传译（仅译音）
日期：2026-03-10

## Vision

- 在播放某个电台频道（`radios/**.radio`）时，UI 增加 **Live** 按钮，一键切换到“同传译音模式”。
- 该模式下：
  - 不显示/不落盘翻译文本；
  - 只输出目标语言语音（模型返回的 audio stream）。
- 目标语言可选，默认记住上次选择。

## Plans

- `docs/plan/v48-radio-live-simint.md`

## Traceability（目标 → 方案 → 验证）

| Goal ID | Plan | Verification（命令 / 证据） |
|---|---|---|
| REQ-0048-010 Live 按钮入口 | `v48-radio-live-simint` | 播放电台时播放器面板出现 Live；点选语言后进入 Live；可 Stop Live 恢复 |
| REQ-0048-020 仅译音输出 | `v48-radio-live-simint` | Live 模式下无文本 UI；听到目标语言语音 |
| REQ-0048-030 radio PCM tap 输入 | `v48-radio-live-simint` | 通过 ExoPlayer + TeeAudioProcessor 解码 PCM 并喂给 realtime WS |

