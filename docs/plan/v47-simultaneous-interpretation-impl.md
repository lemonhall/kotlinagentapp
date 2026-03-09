# v47 同声传译 Implementation Plan

> For Codex: 按任务顺序执行，优先小步可验证提交；每个里程碑完成后跑对应测试，并在行为改动后安装 Debug 包做冒烟验证。

**Goal:** 在 `Files` 页签新增 `simultaneous_interpretation（同声传译）` 独立 app，基于 `qwen3-livetranslate-flash-realtime` 实现麦克风输入、实时译文/译音输出和会话归档，同时保持现有 `instant_translation` 产品线完全不变。

**Architecture:** 新功能拆为 `Files 入口 / Activity+ViewModel / SessionController / Aliyun LiveTranslate Client / Mic Input Source / Audio Player / Archive Manager` 七层。v47 先落地麦克风输入场景，后续 radio 同传只替换输入源，不改协议、播放器和归档格式。

**Tech Stack:** Kotlin, Android Activity + Compose, DashScope `OmniRealtimeConversation`, `AudioRecord`, `AudioTrack`, Robolectric。

---

## Task 1: Files 入口与工作区根目录

**Files:**
- Create: `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardSimultaneousInterpretationRules.kt`
- Modify: `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`
- Modify: `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesViewModel.kt`
- Modify: `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardFragment.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspaceSimultaneousInterpretationTest.kt`
- Test: `app/src/test/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardSimultaneousInterpretationRulesTest.kt`
- Test: `app/src/test/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardSimultaneousInterpretationOpenTest.kt`

**Steps:**
1. 先写三个失败测试，覆盖工作区目录、规则命中和点击入口打开 Activity。
2. 新增 workspace 根目录初始化和 `_STATUS.md` 占位。
3. 给 `FilesViewModel` 增加展示名、副标题和 emoji。
4. 给 `DashboardFragment` 增加点击打开与长按菜单。
5. 新增空壳 `SimultaneousInterpretationActivity` 并注册到 Manifest。
6. 运行三个测试通过。
7. `git add` + `git commit` + `git push`（里程碑 1）。

## Task 2: 同传协议链路与基础 UI

**Files:**
- Create: `app/src/main/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/SimultaneousInterpretationActivity.kt`
- Create: `app/src/main/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/SimultaneousInterpretationScreen.kt`
- Create: `app/src/main/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/SimultaneousInterpretationViewModel.kt`
- Create: `app/src/main/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/SimultaneousInterpretationModels.kt`
- Create: `app/src/main/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/LiveTranslateSessionController.kt`
- Create: `app/src/main/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/AliyunLiveTranslateClient.kt`
- Create: `app/src/main/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/LiveTranslateAudioInputSource.kt`
- Create: `app/src/main/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/LiveTranslateAudioPlayer.kt`
- Test: `app/src/test/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/AliyunLiveTranslateClientTest.kt`
- Test: `app/src/test/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/SimultaneousInterpretationViewModelTest.kt`

**Steps:**
1. 先写 client / viewmodel 失败测试。
2. 基于 `OmniRealtimeConversation` 实现 DashScope client。
3. 接上麦克风 `AudioRecord` 输入和 `AudioTrack` 输出。
4. 用 source transcript 完成事件驱动 `response.create`。
5. 页面展示连接状态、耳机提示、源文、译文和分段列表。
6. 运行相关单测通过。
7. `git add` + `git commit` + `git push`（里程碑 2）。

## Task 3: 归档与回看

**Files:**
- Create: `app/src/main/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/SimultaneousInterpretationArchiveManager.kt`
- Test: `app/src/test/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/SimultaneousInterpretationArchiveManagerTest.kt`
- Modify: `app/src/main/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/LiveTranslateSessionController.kt`
- Modify: `app/src/main/java/com/lsl/kotlin_agent_app/ui/simultaneous_interpretation/SimultaneousInterpretationViewModel.kt`

**Steps:**
1. 先写归档失败测试，覆盖目录命名和产物落盘。
2. 创建 `meta.json`、`events.jsonl`、`segments.jsonl`、`source.md`、`translation.md`、`input_audio.pcm`、`translated_audio.wav`。
3. 在 controller 中把输入音频、输出音频、关键事件和分段结果都接入归档。
4. 页面显示当前归档目录相对路径。
5. 运行归档相关测试通过。
6. `git add` + `git commit` + `git push`（里程碑 3）。

## Verification

- 单测：`./gradlew.bat :app:testDebugUnitTest --tests "*SimultaneousInterpretation*"`
- 入口回归：`./gradlew.bat :app:testDebugUnitTest --tests "*DashboardSimultaneousInterpretation*" --tests "*AgentsWorkspaceSimultaneousInterpretation*"`
- 安装：`./gradlew.bat :app:installDebug`
- 编码检查：`git diff --text`