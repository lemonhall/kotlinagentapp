# v39 Plan：Radio 后台录制（RecordingService + ChunkWriter + `radio record`）

## Goal

交付"电台可录制"的最小闭环，并保持 Everything is FileSystem：

- UI：正在播放电台可一键开始/停止录制；后台/锁屏不中断  
- CLI：`terminal_exec radio record ...` 可控、可审计、可测试  
- VFS：产物落盘到 `workspace/radio_recordings/`，按 10min 切片
- Playback：进入 `radio_recordings/` 可用 App 内播放器直接播放 `*.ogg`（ECN-0005）

## PRD Trace

- PRD-0034：REQ-0034-000 / REQ-0034-001 / REQ-0034-002
- PRD-0034：REQ-0034-010 / REQ-0034-011 / REQ-0034-012 / REQ-0034-013 / REQ-0034-014

## Scope

做（v39）：

- `workspace/radio_recordings/` 根目录初始化（含 `_STATUS.md`、`.recordings.index.json`）
- 前台服务录制引擎（并发 ≤2）：
  - Media3 独立 Player 解码 → PCM
  - MediaCodec Opus 编码 + MediaMuxer OGG 封装（`.ogg`，64kbps）
  - 10min chunk 切片写盘 + 元信息落盘
- CLI：`radio record start|stop|status|list`（最小闭环）
- Files：在 `workspace/` 下把 `radio_recordings/` 目录"命名/简介"做轻度装饰（类似 radios）
- Files：在 `radio_recordings/` 下点击 `*.ogg` 可用 App 内播放器直接播放（路径门禁，ECN-0005）

不做（v39）：

- 不做转录/翻译/TTS（v40+）
- 不做录制任意 URL（只允许 `.radio`）
- 不做 >2 路并发录制（必须硬限制）

## 编码格式决策

录制统一输出 OGG Opus 64kbps（`.ogg`），使用 Android 原生 `MediaCodec`（Opus 编码）+ `MediaMuxer`（OGG 封装），零第三方依赖。

要求 minSdk ≥ 29（Android 10）。目标设备华为 Nova 9（HarmonyOS 2 / Android 11, API 30）已满足。

理由：

- Android `MediaCodec` 原生支持 Opus 编码，`MediaMuxer` 原生支持 OGG 封装（API 29+）
- 不引入任何 JNI/NDK 库
- Opus 是语音优化编码，64kbps 即可达到优秀语音质量，体积约为 AAC 128kbps 的一半
- 完全开源，无专利费
- 下游 ASR 全平台兼容：

| ASR 服务 | OGG/Opus 支持 | 备注 |
|----------|---------------|------|
| 阿里云百炼（Paraformer / SenseVoice） | ✅ | 直接支持 ogg/opus |
| 火山引擎 - 录音文件识别标准版 | ✅ | format=ogg, codec=opus |
| 火山引擎 - 流式语音识别 | ✅ | format=ogg, codec=opus |
| OpenAI Whisper API | ✅ | 直接支持 ogg |

## Acceptance（硬 DoD）

- 并发上限：第三路 `radio record start ...` 必须失败，`error_code=MaxConcurrentRecordings`（或等价稳定码）。  2 个不同电台各 1 路。
- 产物结构：录制开始后必须创建 `{session_id}/_meta.json` 与 `{session_id}/chunk_001.ogg`（允许短延迟）；录制停止后 `state=completed|cancelled|failed` 可解释。  
- 切片策略：单会话 chunk 文件名连续（`chunk_001...`），chunk 时长目标 10min（允许最后一片不足）。  
- 编码验证：产出的 `.ogg` 文件可被 Android MediaPlayer 正常播放，且可直接提交阿里云/火山引擎/OpenAI Whisper ASR 无需转码。
- 回放验证：在 Files 进入 `radio_recordings/` 后，点击 `chunk_*.ogg` 必须触发 App 内播放器播放（ECN-0005）。
- CLI help：`radio record --help` / `radio help record` 必须 `exit_code=0`。  

验证命令（开发机）：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机冒烟：`.\gradlew.bat :app:installDebug` 后录制 1–2 分钟，确认后台不中断 + chunk 可播放。

## Files（规划）

- Workspace 初始化：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`（新增 `workspace/radio_recordings` 初始化）
- 录制模块（建议新包，避免塞进 `radios/`）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_recordings/*`
    - `RadioRecordingService.kt`（Foreground Service）
    - `RecordingSession.kt`（单会话状态机）
    - `ChunkWriter.kt`（PCM → MediaCodec Opus → MediaMuxer OGG，10min 切片）
    - `RecordingMetaV1.kt` / `RecordingsIndexV1.kt`（kotlinx.serialization）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`
    - 新增 `record` 子命令（必要时先做一次子命令拆分重构）
- Files UI 装饰（最小）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesViewModel.kt`

## Steps（Strict / TDD）

1) Analysis：确定 `radio_recordings/` 的落盘结构、错误码集合（含并发上限/路径门禁/状态机失败），以及 CLI 输出字段（`session_id/state/dir/...`）。  
2) TDD Red：为 `radio record --help` / argv 校验 / 路径门禁 / 并发上限写 `TerminalExecToolTest`（或等价测试入口）。  
3) TDD Green：实现 CLI 框架（只做参数解析 + 结构化输出 + 创建 session 目录与 `_meta.json/_STATUS.md`），先不接真实编码。  
4) TDD Red：为 `ChunkWriter` 写纯 Kotlin 单测（切片命名、写入原子性、更新 `_meta.json` 与 `.recordings.index.json` 的一致性）。  
5) TDD Green：接入实际录制链路（Media3 + MediaCodec Opus + MediaMuxer OGG），并把"录制状态 → 文件落盘"打通。  
6) Refactor：把 `RadioCommand.kt` 里与 v39 无关的逻辑保持不动，但避免 `record` 子命令继续膨胀（必要时引入 `RadioSubcommand` 分发）。  
7) Verify：跑 UT；真机录制冒烟（后台/锁屏/切页签）。  

## Risks

- 音频管线复杂且难以纯单测覆盖：必须把"状态机/落盘/CLI"做成可单测，真机仅做最小冒烟。  
- 并发与资源占用：2 路并发要严格限流并有可解释失败，避免 OOM/编码器占用冲突。  
- Opus 编码器可用性：虽然 API 29+ 规范要求支持 Opus 编码，但部分厂商 ROM 可能存在实现差异。v39 第一个 step 应在目标设备（Nova 9）上做编码可行性验证，若不可用则回退到 AAC/.m4a 方案（AAC 除火山引擎流式 ASR 外均兼容，而流式 ASR 本身喂 PCM raw 不涉及容器格式）。
- Opus 编码器可用性：虽然 API 29+ 规范要求支持 Opus 编码，但部分厂商 ROM 可能存在实现差异。v39 第一个 step 应在目标设备（Nova 9）上做编码可行性验证，若不可用则回退到 AAC/.m4a 方案（AAC 除火山引擎流式 ASR 外均兼容，而流式 ASR 本身喂 PCM raw 不涉及容器格式）。
 - Opus 解码可用性（回放）：不同 ROM 对 OGG/Opus 解码支持可能存在差异；v39 增补的“App 内回放”需在 Nova 9 上留真机证据（ECN-0005）。
