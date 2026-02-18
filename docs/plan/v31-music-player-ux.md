# v31 Plan：Files 播放器体验增强（封面/播放模式/歌词/音量）

## Goal

在 v29/v30 已有的播放器运行时（Service/Controller + Media3）基础上，补齐 Files 页签播放器体验闭环：

- 专辑封面渲染（embedded picture + fallback）；
- 4 种播放模式（随机循环/顺序循环/单曲循环/播放一次）；
- 自然结束自动续播（除播放一次外永不停止）；
- 歌词展示（至少 ID3 `USLT`，LRC 时间戳高亮 best-effort；可选 `.lrc` sidecar）；
- 音量控制（mute + slider + 持久化）。

## PRD Trace

- PRD-0029：REQ-0029-060 / REQ-0029-061 / REQ-0029-062 / REQ-0029-063 / REQ-0029-064 / REQ-0029-065 / REQ-0029-066

## Scope

做：

- 播放队列/模式状态机：把“next/prev/track ended”的决策收敛成可单测的纯 Kotlin 逻辑；
- 播放结束自动续播：由状态机驱动 controller 在 ended 时执行 next/loop/stop；
- UI：
  - mini bar 增加封面缩略图；
  - 增加 next/prev、播放模式切换、音量（mute + slider）入口；
  - 歌词以“详情面板/弹窗”方式展示，避免挤压 Files 列表；
- 设置持久化：音量、播放模式至少需持久化。

不做（v31）：

- 均衡器、倍速、在线播放、非 MP3 格式；
- 完整歌词在线拉取；
- 高级随机策略（历史去重、权重等）——只需“随机循环且不停止”，尽量避免连着重复同一首即可。

## Acceptance（硬口径）

见 PRD-0029 的 v31 Acceptance。

## Files（预计）

- UI：
  - `app/src/main/res/layout/fragment_dashboard.xml`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardFragment.kt`
- 播放器运行时：
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/MusicPlayerController.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/MusicNowPlayingState.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/media/Mp3MetadataReader.kt`
  - （新增）`app/src/main/java/com/lsl/kotlin_agent_app/media/PlaybackQueueController.kt`
  - （新增）`app/src/main/java/com/lsl/kotlin_agent_app/media/lyrics/*`
- 单测：
  - `app/src/test/java/com/lsl/kotlin_agent_app/media/PlaybackQueueControllerTest.kt`
  - `app/src/test/java/com/lsl/kotlin_agent_app/media/lyrics/LrcParserTest.kt`

## Steps（Strict / TDD）

1) Analysis：锁定 UI 形态（mini bar + 详情弹窗）与模式切换交互（单按钮循环四种模式）。  
2) TDD Red：先为 `PlaybackQueueController` 写单测（4 种模式下 next/prev/ended 的期望行为）。  
3) TDD Green：实现 `PlaybackQueueController`，并把 `MusicPlayerController` 的队列与 ended 行为改为使用它。  
4) TDD Red：为歌词解析写单测（纯文本/带时间戳的 LRC；异常行不崩）。  
5) TDD Green：实现歌词读取（ID3 USLT）+ 渲染（详情弹窗），并做 LRC 高亮 best-effort。  
6) UI：补封面缩略图、next/prev、模式切换、音量控件；把状态从 `MusicPlayerController.state` 绑定到 UI。  
7) Verify：`.\gradlew.bat :app:testDebugUnitTest`。  

## Risks

- Robolectric 无法稳定驱动真实音频 ended 事件 → ended 逻辑需通过“可单测状态机”证明，播放端仅做事件触发。  
- 歌词格式多样（USLT/LRC/编码/异常文本）→ best-effort 解析，失败仅降级为纯文本或隐藏歌词入口，不得崩溃。  
- UI 复杂度上升 → 采用“mini bar + 详情弹窗”避免挤压 Files 列表；并保持默认状态简洁。  

