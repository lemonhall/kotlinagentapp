# Kotlin Agent App

一个运行在 Android 上的 AI Agent 应用，实现了「口袋里的 Unix」理念 —— 通过自然语言对话驱动文件管理、终端命令、Web 自动化、音乐/电台播放、SSH/IRC 通信等能力，所有数据以人类可读格式透明存储。

An AI Agent app for Android that brings a "pocket Unix" philosophy — drive file management, terminal commands, web automation, music/radio playback, SSH/IRC communication and more through natural language conversation, with all data stored transparently in human-readable formats.

## Architecture / 架构概览

```
┌─────────────────────────────────────────────────┐
│                   Android App                    │
│  ┌───────┐ ┌───────┐ ┌────────┐ ┌───┐ ┌──────┐ │
│  │ Chat  │ │ Files │ │Settings│ │Web│ │ Term │ │
│  │(Compose)│(XML)  │ │ (XML)  │ │   │ │(jedi)│ │
│  └───┬───┘ └───┬───┘ └────────┘ └─┬─┘ └──────┘ │
│      │         │                   │             │
│  ┌───┴─────────┴───────────────────┴───┐         │
│  │         OpenAgenticSdkChatAgent     │         │
│  │  (streaming chat + tool dispatch)   │         │
│  └───┬──────────────┬─────────────┬────┘         │
│      │              │             │               │
│  ┌───┴───┐   ┌──────┴─────┐  ┌───┴────┐         │
│  │TermCmd│   │  Web Tools  │  │  IRC/  │         │
│  │(13+)  │   │  (18 tools) │  │  SSH   │         │
│  └───────┘   └─────────────┘  └────────┘         │
└──────────────────────┬──────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
  openagentic-   agent-browser-  jediterm-
  sdk-kotlin     kotlin          android
  (LLM SDK)     (Web Automation) (Terminal)
```

### 模块组成

| 模块 | 说明 |
|------|------|
| `app/` | Android 应用主模块（Compose + XML ViewBinding 混合 UI） |
| `external/openagentic-sdk-kotlin/` | AI Agent SDK，支持 OpenAI Responses / ChatCompletions / Anthropic Messages 三种协议 |
| `external/agent-browser-kotlin/` | Web 自动化引擎，注入 agent-browser.js 实现 DOM 操作 |
| `external/jediterm-android/` | 终端模拟器，提供 SSH 交互式终端 |

三个 external 模块通过 Gradle composite build (`includeBuild`) 接入，作为 git submodule 管理。

## Features / 功能

### AI Chat（对话）
- 多 Provider 支持：OpenAI、Anthropic、DeepSeek、Kimi、GLM/智谱、Gemini、Grok、阿里通义，以及任意 OpenAI 兼容端点
- 流式回复（SSE），Kotlin Flow 驱动
- 会话持久化（events.jsonl），支持跨会话恢复
- Hook 引擎：上下文溢出控制、重试策略、子 Agent 弹性
- Settings 页面可折叠卡片式 Provider 管理，支持动态添加/删除/拉取模型列表

### Terminal Commands（终端命令）
伪终端架构，无真实 shell 进程，白名单命令注册制，所有执行带审计日志。

| 命令 | 能力 |
|------|------|
| `git` | JGit 实现的 Git 操作（clone/pull/push/commit/log 等） |
| `ssh` | 远程命令执行（JSch），TOFU 主机验证 |
| `irc` | IRC 客户端，长连接绑定会话，自动转发分流 |
| `music` | MP3 播放控制 |
| `radio` | 网络电台播放控制 |
| `cal` | Android 日历读写 |
| `qqmail` | QQ 邮箱收发（JavaMail） |
| `ledger` | 个人记账 |
| `stock` | 股票行情（Finnhub API） |
| `rss` | RSS 订阅阅读 |
| `zip` / `tar` | 压缩/解压 |
| `exchange_rate` | 汇率查询 |
| `tts` | 文字转语音 |

### Web Automation（Web 自动化）
18 个 Web 工具，通过持久化 WebView + agent-browser.js 注入实现：

`web_open` · `web_snapshot` · `web_click` · `web_fill` · `web_type` · `web_select` · `web_scroll` · `web_screenshot` · `web_eval` · `web_query` · `web_back` · `web_forward` · `web_reload` · `web_close` · `web_press_key` · `web_wait` · `web_hover` · `web_scroll_into_view`

### Music Player（音乐播放器）
- MP3 播放，ID3 元数据读取（标题/艺术家/专辑/封面/歌词）
- 播放模式：随机循环、顺序循环、单曲循环、播放一次
- 后台/锁屏播放（Media3 + MediaSession + 前台服务）
- Files 页签内嵌迷你播放条
- LRC 歌词时间轴支持

### Radio Player（网络电台）
- Radio Browser API 集成，按国家/地区浏览电台
- `.radio` 文件格式（JSON），收藏夹系统
- 后台播放，与音乐播放器共享 MediaSession
- 可选收听历史记录（JSONL，需用户授权）

### SSH Terminal（SSH 终端）
- jediterm-android 提供完整终端模拟器
- 支持密码和私钥认证
- Known hosts 管理（TOFU 策略）

### IRC Client（IRC 客户端）
- 长连接绑定 Agent 会话
- 消息收发，游标去重
- 可选自动转发到 Agent（LLM 分流判断）
- Chat 页签三色状态指示灯

### File Management（文件管理）
- `.agents/` 工作区浏览与管理
- 外部文件导入（Intent ACTION_VIEW / ACTION_SEND → inbox）
- NAS/SMB 网络文件访问与媒体流播放

### ASR & Translation（语音识别与翻译）
- 阿里云 DashScope ASR（qwen3-asr-flash-filetrans）
- 基于 LLM 的文本翻译，支持批量分片

## Workspace Structure / 工作区结构

```
.agents/
├── workspace/
│   ├── inbox/           # 文件导入目录
│   ├── musics/          # MP3 音乐库
│   ├── radios/          # 电台配置
│   │   └── favorites/   # 收藏电台
│   └── ssh/
│       └── known_hosts  # SSH 主机密钥
├── skills/
│   └── <skill>/secrets/.env  # 各 Skill 密钥
├── artifacts/
│   ├── terminal_exec/runs/   # 命令审计日志
│   ├── web_screenshots/      # Web 截图
│   └── ssh/exec/             # SSH 输出
└── sessions/
    └── <session_id>/
        ├── events.jsonl      # 会话事件流
        └── meta.json         # 会话元数据
```

## Design Philosophy / 设计哲学

- **Everything is a file, everything is a directory** — Unix 哲学在移动端的延伸，应用即目录，操作即命令
- **UI & CLI 等价** — 所有功能同时提供图形界面和命令行接口
- **数据透明** — 人类可读格式（JSON、.env、纯文本），用户可直接查看和编辑
- **.env 一等公民** — 配置以 .env 文件为载体，每个 Skill 独立管理密钥

## Tech Stack / 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.0.21 |
| 构建 | Gradle 8.7 + Version Catalog |
| Android | compileSdk 35, minSdk 24, targetSdk 35 |
| UI | Jetpack Compose + Material 3（Chat）/ XML + ViewBinding（其余页签） |
| 异步 | Kotlin Coroutines 1.8.1 + Flow |
| 序列化 | kotlinx-serialization-json 1.6.3 |
| 网络 | OkHttp 4.12.0 |
| 媒体 | AndroidX Media3 1.4.1（ExoPlayer + MediaSession） |
| Git | JGit 5.13.5 |
| SSH | JSch 0.2.17 |
| 终端 | jediterm-android |
| 测试 | JUnit 4 + Robolectric 4.12.2 |

## Prerequisites / 开发环境

- Android Studio (latest stable)
- JDK 17+
- Android SDK Platform 35
- Windows 11 + PowerShell（示例命令以 PowerShell 为主）

## Quickstart / 快速开始

```powershell
# 初始化 submodule
git submodule update --init --recursive

# 构建 Debug APK
.\gradlew.bat :app:assembleDebug

# 安装到设备
.\gradlew.bat :app:installDebug

# 运行单元测试
.\gradlew.bat :app:testDebugUnitTest

# Lint 检查
.\gradlew.bat :app:lintDebug
```

## Local .env (Debug only) / 本地配置

仓库根目录支持 `.env` 文件（已 gitignore），Debug 构建会读取其中的默认值注入到 App 配置。

```env
PROVIDER=openai
OPENAI_API_KEY=sk-...
OPENAI_BASE_URL=https://api.openai.com/v1
MODEL=gpt-4.1-mini

ANTHROPIC_API_KEY=sk-ant-...
ANTHROPIC_BASE_URL=https://api.anthropic.com
ANTHROPIC_MODEL=claude-sonnet-4-20250514

DEEPSEEK_API_KEY=sk-...
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
DEEPSEEK_MODEL=deepseek-chat

TAVILY_URL=https://api.tavily.com
TAVILY_API_KEY=tvly-...

HTTP_PROXY=http://127.0.0.1:7897
HTTPS_PROXY=http://127.0.0.1:7897
```

## Documentation / 文档

- `docs/prd/` — 产品需求文档（PRD-0001 ~ PRD-0030）
- `docs/plan/` — 版本执行计划（v1 ~ v33+）
- `docs/ecn/` — 工程变更通知
- `docs/research/` — 技术调研
- `AGENTS.md` — 项目约定与 Agent 开发指南

## Security / 安全提示

- 不要将 API Key、私钥、证书等敏感信息提交到 Git
- `local.properties` 属于本机配置，不应提交或共享
- Skills 密钥存储在 `.agents/skills/<name>/secrets/.env`，与代码隔离
- 终端命令白名单制，禁止 shell 注入（`;` `&&` `|` `>` 等 token 被拦截）

## License

Private repository. All rights reserved.
