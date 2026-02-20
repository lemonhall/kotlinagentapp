# ECN-0005: v39 录音产物（OGG/Opus）支持 App 内播放器直接播放

## 基本信息

- **ECN 编号**：ECN-0005
- **关联 PRD**：PRD-0034、PRD-0029
- **关联 Plan**：v39-radio-recording
- **关联 Req ID**：新增 REQ-0034-015
- **发现阶段**：v39 验收准备（可用性缺口）
- **日期**：2026-02-20

## 变更原因

v39 已明确录制产物为 `workspace/radio_recordings/**.ogg`（10min 切片），且要求“可被 Android MediaPlayer 正常播放”。但在实际验收/自测路径上：

- 用户进入 `radio_recordings/` 后，希望**不导出文件、不跳转外部 App**，可直接用 App 内播放器快速回放 chunk，判断录音质量与连续性。
- 这属于“电台可录制”最小闭环的可用性要求，与 “Everything is FileSystem（Files 即验收入口）”一致。

## 变更内容

### 原设计（v39）

- 录制落盘为 `.ogg` 切片；
- 验收口径偏向“系统播放器可播放 / 可提交 ASR 无需转码”；
- App 内播放器能力边界主要覆盖：`workspace/musics/**.mp3` 与 `workspace/radios/**.radio`。

### 新设计（本 ECN）

在不扩大“录制输入门禁”（仍仅允许 `.radio`）的前提下，**扩展播放器播放范围**：

- Files 中进入 `workspace/radio_recordings/` 目录后，点击任意 `*.ogg` chunk 文件，**使用 App 内播放器直接播放**；
- 需有明确路径门禁：仅允许 `workspace/radio_recordings/**.ogg`（或等价安全子树）触发播放；
- 复用既有播放器 UI（mini play bar/底部面板），不新增复杂队列能力；仅要求 play/pause/stop 可用；
- 保持后台/锁屏播放策略与现有播放器一致（不引入新的后台策略分叉）。

## 影响范围

- 受影响的 Req ID：
  - 新增：REQ-0034-015
- 受影响的计划：
  - v39：Acceptance 与 Scope 增补“录音回放（ogg）”条目
- 受影响的代码（预计）：
  - 播放器门禁与数据源支持（支持 file:// 的 OGG/Opus）
  - Files 点击行为（ogg → 播放）
- 受影响的测试（建议）：
  - 纯 Kotlin：路径门禁与播放入口参数校验单测
  - （可选）Robolectric：ogg 文件点击触发播放入口的 UI 测试

## 处置方式

- [x] v39 Plan 已同步更新（在 v39 Acceptance/Scope 增补“ogg 回放”）
- [x] PRD 已同步更新（在 PRD-0034 v39 Requirements 新增 REQ-0034-015，并标注“由 ECN-0005 变更”）
- [ ] 追溯矩阵已同步更新（若存在 v39-index / trace 表）
- [ ] 相关测试已补齐（播放门禁/点击行为）

