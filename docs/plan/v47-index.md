# v47 Index：Files 同声传译（Qwen3 LiveTranslate Realtime）
日期：2026-03-10

## Vision

- 在 Files 页签下新增独立 app：`simultaneous_interpretation`（同声传译）。
- 明确保持现有 `instant_translation` 不变；两条产品线分别服务“短句轮转”和“连续同传”。
- 第一版聚焦麦克风输入 + 耳机输出场景，采用阿里云百炼 `qwen3-livetranslate-flash-realtime`。
- 设计上把“音频输入源”与“实时翻译会话”解耦，为后续 radio 同传复用预留底座。

## Plans

- `docs/plan/v47-simultaneous-interpretation.md`

## Traceability（目标 → 方案 → 验证）
| Goal ID | Plan | Verification（命令 / 证据） |
|---|---|---|
| REQ-0047-010 Files 独立入口 | `v47-simultaneous-interpretation` | Files 根目录出现 `simultaneous_interpretation`，可打开独立 Activity |
| REQ-0047-020 同传协议链路 | `v47-simultaneous-interpretation` | WebSocket 建连；持续发送 PCM；收到 `response.audio.delta` / `response.audio_transcript.done` |
| REQ-0047-030 归档可回看 | `v47-simultaneous-interpretation` | 会话目录中有 `meta.json`、`segments.jsonl`、`source.md`、`translation.md`、`translated_audio.wav` |
| REQ-0047-040 耳机场景优先 | `v47-simultaneous-interpretation` | 当前输出为扬声器时出现明确风险提示 |
| REQ-0047-050 可复用到 radio | `v47-simultaneous-interpretation` | 代码边界存在 `AudioInputSource` 抽象，radio 未来只需新增输入源实现 |

## Notes

- 该版本当前仍是设计文档，尚未开始实现。
- 未来 radio 同传会优先复用 v47 的“实时翻译会话 + 播放器 + 归档格式”底座。
- SMB 电影场景暂不纳入 v47；其关键难点是“保留原声 + 译音混音”，后续单独设计。
