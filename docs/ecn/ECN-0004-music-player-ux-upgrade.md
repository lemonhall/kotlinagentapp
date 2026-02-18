# ECN-0004: Music player UX 升级（封面/播放模式/歌词/音量）

## 基本信息

- **ECN 编号**：ECN-0004
- **关联 PRD**：PRD-0029
- **关联 Req ID**：新增（REQ-0029-060 ~ REQ-0029-066）
- **发现阶段**：v31-music-player-ux（规划阶段）
- **日期**：2026-02-18

## 变更原因

现状（v29/v30）在 Files 页签仅提供极简 mini play bar（title/subtitle/progress + play/pause/stop），用户反馈：

1) UI 过于朴素：未渲染 MP3 专辑封面；  
2) 播放体验不完整：缺少“播放完成后自动下一曲/不停止”的模式控制；  
3) 歌词未展示：若曲目自带歌词（如 ID3 USLT）希望可渲染；  
4) 缺少音量控制：至少需要 mute + 音量滑块。  

这些诉求属于“播放器体验闭环”的关键能力，且与 v30 已落地的 ID3 读写能力（lyrics/coverArt）一致，因此需要在 PRD-0029 中新增 v31 范围的 UX 需求与验收口径。

## 变更内容

### 原设计（PRD-0029）

- v29 交付最小播放器闭环（仅 mp3 + metadata + mini bar + 后台/锁屏继续播放）。
- v29 Non-goals 中明确不做：歌词、音量增强等体验增强项。

### 新设计（新增 v31 UX 需求）

在不改变“只在 `musics/` 子树启用播放器能力”边界的前提下，新增 v31 UX 需求（REQ-0029-060 ~ REQ-0029-066）：

- mini bar/详情面板渲染封面（优先 embedded picture；无则 fallback 占位）；
- 增加 4 种播放模式：随机循环 / 顺序循环 / 单曲循环 / 播放一次（不循环）；
- 播放结束时根据模式自动续播（除“播放一次”外均不停止）；
- 渲染歌词：优先 ID3 USLT；可选支持同名 `.lrc` sidecar；若包含时序（LRC 时间戳）则随播放高亮；
- 音量控制：mute + 音量滑块，并保持用户设置（持久化）。

## 影响范围

- 受影响的 Req ID：
  - 新增：REQ-0029-060 ~ REQ-0029-066
- 受影响的 vN 计划：
  - 新增：v31-music-player-ux
- 受影响的测试：
  - 新增：播放队列/模式状态机单测、歌词解析单测（Robolectric/纯 Kotlin）
- 受影响的代码文件（预计）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/*`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardFragment.kt`
  - `app/src/main/res/layout/fragment_dashboard.xml`

## 处置方式

- [x] PRD 已同步更新（在 PRD-0029 中新增 v31 需求段落，并标注“由 ECN-0004 变更”）
- [ ] v31 计划已建立并完成 DoD 硬度自检
- [ ] 追溯矩阵已同步更新（v31-index）
- [ ] 相关测试已补齐

