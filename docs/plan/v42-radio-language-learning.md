# v42 Plan：文件类型感知渲染 + 双语字幕视图 + 播放定位高亮

## Goal

把"转录/翻译文件"从原始 JSON 变成可阅读、可交互的字幕体验：

- Files 模块引入可插拔的文件类型感知渲染器机制
- `*.translation.json` 渲染为双语字幕视图
- 点击时间戳定位播放对应 `chunk_NNN.ogg` + 高亮当前句

## PRD Trace

- PRD-0034：REQ-0034-100 / REQ-0034-101 / REQ-0034-102

## Scope

做（v42）：

- `VfsFileRenderer` 可插拔渲染器架构（Files 模块级基础设施）
- 首批渲染器：
  - `*.translation.json` → 双语字幕视图（原文/译文/双语三种显示模式切换）
  - `*.transcript.json` → 原文字幕视图（双语字幕视图的简化版，复用组件）
  - `_task.json`（transcript/translation task） → 任务进度卡片
- 播放定位：从字幕视图点击时间戳 → 播放对应 `chunk_NNN.ogg` 的对应位置
- 高亮联动：播放进行时，当前句自动高亮，字幕自动滚动跟随

不做（v42）：

- 不做 language-tutor Agent 面板（v43）
- 不做 TTS 双语听力生成（v43）
- 不做实时字幕（v45+）

## 文件类型感知渲染架构

```kotlin
interface VfsFileRenderer {
    /** 是否能渲染此文件（基于文件名、父路径、或文件内容 schema） */
    fun canRender(fileName: String, parentPath: String): Boolean
    /** 渲染优先级（多个 renderer 匹配时取最高） */
    val priority: Int get() = 0
    /** 返回渲染用的 Composable */
    @Composable
    fun Render(file: VfsFile, modifier: Modifier)
}
```

渲染器注册表（`VfsRendererRegistry`）：

```kotlin
class VfsRendererRegistry {
    private val renderers = mutableListOf<VfsFileRenderer>()
    fun register(renderer: VfsFileRenderer)
    /** 找到最匹配的渲染器，无则返回 null（回退到默认 JSON/文本查看） */
    fun findRenderer(fileName: String, parentPath: String): VfsFileRenderer?
}
```

v42 注册的渲染器：

| 文件模式 | 渲染器 | 匹配规则 |
|----------|--------|----------|
| `*.translation.json` | `BilingualSubtitleRenderer` | 文件名以 `.translation.json` 结尾 |
| `*.transcript.json` | `TranscriptSubtitleRenderer` | 文件名以 `.transcript.json` 结尾 |
| `_task.json`（在 transcripts/ 下） | `TranscriptTaskCardRenderer` | 文件名为 `_task.json` 且父路径含 `transcripts/` |

未来其他模块可按同样模式注册自己的渲染器，不侵入 Files 核心代码。

## 双语字幕视图设计

```
┌─────────────────────────────────────────────┐
│  NHK World · chunk_001 · 00:00 - 10:00      │
│  [原文] [译文] [双语]               advancement│
│─────────────────────────────────────────────│
│  ▶ 00:00  こんにちは、NHKワールドニュースです     │
│           你好，这里是NHK世界新闻               │
│                                             │
│  ● 00:03  今日のトップニュースをお伝えします       │  ← 当前播放高亮
│           为您播报今天的头条新闻                 │
│                                             │
│    00:07  まず、経済ニュースです                 │
│           首先是经济新闻                        │
│  ...                                        │
└─────────────────────────────────────────────┘
```

交互行为：

- 点击时间戳 → 定位播放（详见下方"播放定位逻辑"）
- 顶部 tab 切换显示模式：原文 / 译文 / 双语对照
- 播放中自动滚动 + 当前句高亮
- `TranscriptSubtitleRenderer` 复用同一组件，只是不显示译文行

## 播放定位逻辑

点击字幕视图中某个 segment 的时间戳时，根据当前播放状态分三种情况：

| 当前状态 | 行为 |
|----------|------|
| 没在播放任何内容 | 启动播放该 `chunk_NNN.ogg`，seek 到 `startSec` |
| 正在播放同一个 chunk | 直接 seek 到 `startSec` |
| 正在播放其他内容 | 切换到该 `chunk_NNN.ogg`，seek 到 `startSec` |

chunk 文件定位规则：字幕视图知道自己渲染的是哪个 `chunk_NNN.translation.json`，对应的音频文件在同级目录的上一层（`../{sourceChunk}`，即 `chunk_NNN.ogg`）。`sourceChunk` 字段已在 v40/v41 的 schema 中定义。

高亮联动：

- ViewModel 持有 `currentPlaybackPositionSec: StateFlow<Double>`
- 字幕列表根据 `currentPlaybackPositionSec` 计算当前 segment index
- LazyColumn 自动 `animateScrollToItem` 到当前 segment

## Acceptance（硬 DoD）

- 渲染器架构：`VfsRendererRegistry` 可注册/查找渲染器；未匹配的文件回退到默认查看器。
- 双语字幕：点击 `chunk_001.translation.json` 必须进入双语字幕视图，支持原文/译文/双语三种模式切换。
- 原文字幕：点击 `chunk_001.transcript.json` 必须进入原文字幕视图。
- 任务卡片：点击 transcripts 目录下的 `_task.json` 必须渲染为进度卡片（而非原始 JSON）。
- 定位播放：点击任意时间戳必须触发播放定位到对应 chunk 的对应时间点，三种播放状态场景均正确处理。
- 高亮联动：播放进行时，当前句高亮随播放位置移动（允许 ±0.5 秒近似）。
- CLI help：无新增 CLI（v42 纯 UI 版本）。

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机：安装后打开 Files → 录制会话 → transcripts → translation 文件 → 字幕视图可用、定位播放可用

## Files（规划）

- 渲染器架构（Files 模块基础设施）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/VfsFileRenderer.kt`（接口）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/VfsRendererRegistry.kt`
- 字幕渲染器：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/BilingualSubtitleRenderer.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/TranscriptSubtitleRenderer.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/renderer/TranscriptTaskCardRenderer.kt`
- 字幕 UI 组件（Compose）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleScreen.kt`（字幕视图主屏）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SubtitleViewModel.kt`（播放定位 + 高亮状态）
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/subtitle/SegmentRow.kt`（单行 segment 组件）
- Files 集成：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesViewModel.kt`（接入 RendererRegistry）

## Steps（Strict / TDD）

1) Analysis：确定 `VfsFileRenderer` 接口最小契约；确定字幕视图需要的数据结构（segments + 时间戳 + 当前播放位置）；确定从文件路径定位音频 chunk 的规则（`sourceChunk` 字段 → 上级目录）。
2) TDD Red：`VfsRendererRegistry` 单测 — 注册/查找/优先级/未匹配回退。
3) TDD Green：实现 `VfsRendererRegistry` + 三个渲染器的 `canRender` 逻辑。
4) TDD Red：`SubtitleViewModel` 单测 — 加载 translation.json → segments 列表；显示模式切换（原文/译文/双语）。
5) TDD Red：播放定位单测 — 三种播放状态场景（未播放/同 chunk/不同内容），用 fake player 验证 seek 调用。
6) TDD Red：高亮联动单测 — 给定 `currentPlaybackPositionSec`，验证计算出的 `currentSegmentIndex` 正确。
7) TDD Green：实现 `SubtitleScreen` + `SubtitleViewModel` + `SegmentRow`，接入真实播放器。
8) TDD Green：实现 `TranscriptTaskCardRenderer`（进度卡片）。
9) Verify：UT 全绿；真机字幕交互冒烟（打开 translation.json → 字幕视图 → 点击定位 → 高亮跟随）。

## Risks

- UI 工作量：双语字幕视图 + 播放联动是 v42 的主要工作量，建议先做最小可用版本（纯文本列表 + seek），动画/滚动优化后续迭代。
- 渲染器架构的侵入性：`VfsRendererRegistry` 需要在 Files 的文件点击流程中插入一个分发点，需确认不破坏现有文件查看行为。
- 播放器状态管理：字幕视图需要和播放器双向通信（seek + position 监听），需确认现有 Radio 播放器架构支持这种交互。