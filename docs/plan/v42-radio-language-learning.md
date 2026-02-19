# v42 Plan：语言学习交互（双语字幕视图 + 时间定位播放 + language-tutor + 双语听力 TTS）

## Goal

把“转录/翻译文件”变成可学习、可交互的体验闭环：

- `*.translation.json` 渲染成双语字幕视图（非原始 JSON）
- 点击时间戳定位播放 + 高亮当前句
- 长按句子唤起 language-tutor agent 面板
- 生成双语听力 TTS（交替/仅译文）

## PRD Trace

- PRD-0034：REQ-0034-100 / REQ-0034-101 / REQ-0034-102 / REQ-0034-103 / REQ-0034-104

## Scope

做（v42）：

- Files 侧的“文件类型感知渲染”最小落地：
  - `*.translation.json` → 双语字幕视图
  - `*.transcript.json` → 原文字幕视图（可简化）
  - `_task.json` → 任务状态卡片（进度条）
- 播放定位：从字幕视图跳转到对应 `chunk_NNN.m4a` 的时间点播放
- 内置技能：
  - `app/src/main/assets/builtin_skills/language-tutor/SKILL.md`
- TTS 双语听力任务（依赖 v43 的 TtsService；若 v43 未完成，可先以现有 TTS CLI 能力做最小实现）
- CLI：`radio tts start|status|cancel`

不做（v42）：

- 不做实时字幕（v44）
- 不做复杂学习系统（生词本/复习计划等）

## Acceptance（硬 DoD）

- 渲染：点击 `chunk_001.translation.json` 必须进入双语字幕视图（原文/译文/双语切换）。  
- 定位：点击任意时间戳必须触发播放定位，且 UI 可见“当前句高亮”随播放移动（允许近似）。  
- Agent：长按某句必须打开 language-tutor 面板，并把选中句 + 周边句 + 语言对作为上下文传入。  
- TTS：`radio tts start --task <transcriptTaskId> --mode interleaved|target_only` 必须创建 `audio_bilingual/_task.json` 并逐 chunk 产出音频文件（允许先支持少数语言）。  

验证命令：

- `.\gradlew.bat :app:testDebugUnitTest`
- 真机：安装后打开 Files → 录制会话 → transcripts → translation 文件 → 字幕视图可用

## Files（规划）

- Files UI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/ui/dashboard/FilesViewModel.kt`（增加 radio_recordings/ 与 transcript 目录的轻度装饰）
  - 新增字幕视图 UI（放在现有 UI 架构下：Fragment/Compose 以工程现状为准）
- 内置 skill：
  - `app/src/main/assets/builtin_skills/language-tutor/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`（安装 skill）
- TTS：
  - `app/src/main/java/com/lsl/kotlin_agent_app/radio_tts/*`（任务/落盘/进度）
- CLI：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/radio/RadioCommand.kt`（新增 `tts` 子命令）

## Steps（Strict / TDD）

1) Analysis：确定字幕视图需要的最小数据结构（segments + 时间戳 + 当前播放位置），以及从文件路径定位音频 chunk 的规则。  
2) TDD Red：为“识别文件类型 → 选择渲染器/路由”写 UT（至少覆盖 `.translation.json`）。  
3) TDD Green：实现双语字幕视图基础 UI + 显示模式切换。  
4) TDD Red：定位播放与高亮的 UT（可在 ViewModel 层用 fake player/controller 验证时序）。  
5) TDD Green：接入真实播放器定位（seek）与高亮联动。  
6) TDD Red：language-tutor 上下文组装的测试（选中句/周边句/语言对/用户水平）。  
7) TDD Green：接入 agent 面板与内置 skill 安装。  
8) TDD Red/Green：实现 `radio tts`（先把落盘/任务进度跑通，再逐步接入真实 TTS provider）。  
9) Verify：UT 全绿；真机字幕交互冒烟。  

## Risks

- UI 工作量可能较大：建议先只做 `translation.json` 的双语字幕视图，其他类型逐步补齐。  

