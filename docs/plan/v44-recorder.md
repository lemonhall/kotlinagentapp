# v44：Recorder（通用录音机）

## Goal

- 在 Files 页签新增 `recordings/` 入口（🎙），支持麦克风录音落盘为 session 目录，并复用既有 v40/v41/v42 的转录、翻译、双语播放 pipeline。

## PRD Trace

- REQ-0035-010 / 020 / 030 / 040 / 050（见 `docs/prd/PRD-0035-Recorder.md`）

## Scope

### In

- `workspace/recordings/` 根目录：🎙 图标、长按菜单「开始录音」
- 录音页：开始、暂停/继续、停止
- 录制产物：`workspace/recordings/rec_{timestamp}_{random}/` 下 `_meta.json` + `chunk_*.ogg`
- session 目录长按菜单：播放、转录、转录+翻译、双语播放、重命名、删除（复用既有逻辑并扩展到 recordings root）
- 后台录制：Foreground Service + 通知跳回录音页 + WakeLock（复用模式）

### Out

- 导出 mp3/wav、分享、音质设置（PRD P3）
- 新增底部导航 Tab（PRD 明确不新增）

## Acceptance（硬口径）

1. Files 处于 `workspace/` 时，`recordings` 目录显示 🎙 图标；单击进入目录，长按弹出「开始录音」菜单。
2. 点击开始录音后进入全屏录音页：显示时长、波形动画；可暂停/继续；停止后进入保存页（可改名、可勾选自动转录+翻译、可选目标语言）。
3. 保存后生成目录：`workspace/recordings/rec_*/_meta.json` 与至少一个 `chunk_001.ogg`；`_meta.json` 标注 `source=microphone` 且写入 `pipeline` 配置（若启用自动）。
4. 在 `workspace/recordings/` 内长按 session 目录弹出菜单：▶ 播放 / 📝 转录 / 🌐 转录+翻译 / 🎧 双语播放 / ✏️ 重命名 / 🗑 删除；其中转录/翻译/双语播放复用 v40/v41/v42 并对 recordings root 生效。
5. 录制切后台不中断：通知栏可见录制状态，点击通知回到录音页；停止后通知取消。

## Files（预计改动）

- `app/src/main/java/com/lsl/kotlin_agent_app/recordings/*`（新增：root/路径解析）
- `app/src/main/java/com/lsl/kotlin_agent_app/recorder/*`（新增：录音 Activity + FGS + AudioRecord session）
- `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/DashboardFragment.kt`（入口/长按菜单/会话菜单适配 recordings）
- `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesViewModel.kt`（特殊图标 + session 摘要渲染支持 recordings）
- `app/src/main/java/com/lsl/kotlin_agent_app/media/MusicPlayerController.kt`（允许播放 `workspace/recordings/*.ogg`）
- `app/src/main/java/com/lsl/kotlin_agent_app/radio_transcript/*`（pipeline/transcript/paths 适配 recordings）
- `app/src/main/java/com/lsl/kotlin_agent_app/radio_bilingual/player/BilingualSessionLoader.kt`（加载 recordings session）
- `app/src/main/AndroidManifest.xml`（权限 + Activity/Service）
- `app/src/main/res/layout/*`（录音页/保存页布局）
- `app/src/test/java/**`（新增/更新：路径解析与 recordings root 流程单测）

## Steps（Strict）

1. TDD Red：补路径解析/loader/transcript/pipeline 对 recordings root 的失败测试
2. TDD Green：实现 session root 解析 + 各模块改造通过单测
3. 实现录音 Activity + Service（先录制落盘 + 停止保存）
4. 接入 Files 入口（🎙 图标 + 长按开始录音）与 session 菜单（播放/转录/翻译/双语播放/重命名/删除）
5. 验证：`.\gradlew.bat :app:testDebugUnitTest`；（可选）`.\gradlew.bat :app:installDebug`

## Risks

- Android 版本差异导致 FGS/microphone 权限行为不一致：优先保证 API 29+ 可用；对高版本补齐 `FOREGROUND_SERVICE_MICROPHONE`。
- 既有 v40/v41/v42 默认写死 `radio_recordings/`：必须通过统一的 session root 解析避免断链。

