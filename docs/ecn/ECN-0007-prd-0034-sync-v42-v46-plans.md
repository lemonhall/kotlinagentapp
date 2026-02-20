# ECN-0007: PRD-0034 与 v42-v46 plan 对齐

## 基本信息

- **ECN 编号**：ECN-0007
- **关联 PRD**：PRD-0034
- **关联 Req ID**：REQ-0034-080 / REQ-0034-081 / REQ-0034-100 / REQ-0034-101 / REQ-0034-102 / REQ-0034-103 / REQ-0034-104 / REQ-0034-130 / REQ-0034-131 / REQ-0034-132 / REQ-0034-180 / REQ-0034-181 / REQ-0034-182
- **发现阶段**：文档对齐（以 `docs/plan/v41-*` ~ `v46-*` 为准反向修订 PRD）
- **日期**：2026-02-20

## 变更原因

`docs/plan/` 的 v42-v46 计划已形成稳定口径，但 PRD-0034 中 v42-v45 的版本映射与部分 Req 内容仍沿用旧拆分，导致：

- v42 计划（双语 session 播放器）与 PRD 的 v42（language-tutor + TTS）定义冲突；
- v43/v44/v45/v46 的“版本号 → 交付内容”在 PRD 与 plan 之间不一致；
- live translation 的需求编号（REQ-0034-180/181/182）在 PRD 中与 plan 的切分不一致。

本 ECN 的目标是：**以 plan 为准**，把 PRD 作为稳定锚点同步到同一口径，消除文档漂移。

## 变更内容

### 原设计（摘要）

- PRD-0034 将 v42 定义为“语言学习交互”（language-tutor + TTS），并把 ASR/TTS 模块化放在 v43。
- PRD-0034 将实时翻译拆为 v44/v45，并包含一组旧的 live MVP Req（REQ-0034-150~153）。

### 新设计（以 plan 为准）

- v41：离线转录/翻译目录结构简化（`transcripts/` + `translations/` 平铺），pipeline 状态挂 `_meta.json`。
- v42：双语 session 播放器（长按 session 目录进入；多 chunk 顺序播放；字幕高亮/自动滚动；变速；点击字幕定位）。
- v43：language-tutor Agent + TTS 双语听力生成（`radio tts ...`）。
- v44：ASR/TTS Service 编排层 + 通道隔离 + Chat 语音输入最小闭环。
- v45：实时翻译管线 + MixController + 三模式混音 + streaming 字幕追加。
- v46：全链路落盘 + AudioFocusManager + `radio live` 落盘参数扩展。

同时：

- 调整 `REQ-0034-100~104` 的语义与版本归属，使其与 v42/v43 plan 的 PRD Trace 对齐。
- 调整 `REQ-0034-180~182` 的语义与版本归属，使其与 v45/v46 plan 的 PRD Trace 对齐。
- 移除 PRD 中旧的 live MVP Req（REQ-0034-150~153），其设计意图已被 v45/v46 的新方案覆盖。

## 影响范围

- 受影响的 Req ID：见“基本信息”
- 受影响的 vN 计划：`docs/plan/v42-radio-dual-language-session-player.md`、`docs/plan/v43-radio-dual-language.md`、`docs/plan/v44-asr-tts-modularization.md`、`docs/plan/v45-radio-live-translation-full.md`、`docs/plan/v46-radio-live-AudioFocusManager.md`
- 受影响的测试：文档变更；测试追溯链需在后续实现时按 plan 写入
- 受影响的代码文件：无（本 ECN 仅同步文档口径）

## 处置方式

- [x] PRD 已同步更新（v42-v46 版本映射 + Req 内容对齐，并在相关段落标注“由 ECN-0007 变更”）
- [ ] vN-index 追溯矩阵同步（如项目对该矩阵有硬性要求，需在对应 index 中补充对齐记录）
