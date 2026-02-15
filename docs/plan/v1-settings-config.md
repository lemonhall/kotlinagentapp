# v1 Plan：Settings Tab（XML）配置持久化（占位）

## Goal

提供一个最小可用的设置页：可编辑并持久化 `base_url` / `api_key` / `model`，后续 AgentService 读取该配置即可接入 SDK。

## PRD Trace

- REQ-0001-010（LLM 配置）
- REQ-0001-011（Tools keys，本轮先不实现 UI，仅预留数据结构）

## Scope

做：
- DataStore（或 SharedPreferences）持久化 `base_url` / `api_key` / `model`
- Settings 页 UI（XML）提供编辑与保存
- 单测覆盖“写入后读回”

不做：
- 不做加密存储（可在 v2 引入 Jetpack Security）
- 不做 tool keys UI（仅留扩展点）

## Acceptance（硬口径）

1. 设置值写入后可读回（单测 pass）。
2. 构建/单测命令通过：
   - `.\gradlew.bat :app:testDebugUnitTest`

## Notes

本计划不阻塞 v1 的 M1；待 M1 完成后再进入。

