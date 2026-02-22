# Agent Notes (kotlin-agent-app)

## Project Overview

这是一个 Android 端的聊天型 Agent 应用工程（Bottom Navigation 模板起步）。目标是逐步接入 `openagentic-sdk-kotlin` / `agent-browser-kotlin`，实现「对话 + 工具调用 + Skills + App 内工作区（`.agents/*`）」并预留 WebView 自动化能力。

## Quick Commands (PowerShell)

- 前置：安装 Android SDK Platform 35（当前 `compileSdk=35`）
- 首次克隆后初始化 submodule：`git submodule update --init --recursive`
- Install deps / Sync: `.\gradlew.bat --version`（确认 wrapper 可用；依赖会在首次构建自动下载）
- Build (debug): `.\gradlew.bat :app:assembleDebug`
- Install to device (debug): `.\gradlew.bat :app:installDebug`（需要 `adb devices` 可看到真机/模拟器）
- Lint: `.\gradlew.bat :app:lintDebug`
- Test (unit): `.\gradlew.bat :app:testDebugUnitTest`
- Test (instrumented): `.\gradlew.bat :app:connectedDebugAndroidTest`（需要已启动模拟器或连接真机）
- Clean: `.\gradlew.bat clean`

> 说明：本仓库默认使用 PowerShell；连续执行命令用 `;` 分隔（例如 `cd app ; ls`），不要用 `&&`。

### Gradle 性能（本地开发）

- 单测默认开启 Configuration Cache；第一次会慢一些，后续会明显变快。
- 如需更快（多核并行跑 UT）：`.\gradlew.bat :app:testDebugUnitTest -PtestMaxParallelForks=4`
- 日常省时间跑法：
  - 指定单测：`.\gradlew.bat :app:testDebugUnitTest --tests "com.lsl.kotlin_agent_app.ExampleUnitTest"`
  - 只验编译：`.\gradlew.bat :app:compileDebugUnitTestKotlin`
- 如果遇到 “另一个程序正在使用此文件”：先执行 `.\gradlew.bat --stop` 再重试。

## 本地环境（Windows 11 + PowerShell 7.x）

在本仓库中执行命令时，遵循以下约定：

### Shell 环境

- 默认 Shell 为 **PowerShell 7.x**（非 bash），所有命令片段优先使用 PowerShell 原生语法。
- 命令连续执行使用 `;`（PS5/PS7 通用语句分隔符）。避免使用 `&&` / `||`，除非明确需要 PS7 的管道链条件控制语义。

### 常见 bash 写法的 PowerShell 对照

| bash 写法 | PowerShell 写法 |
|---|---|
| `ls -la` | `Get-ChildItem -Force` |
| `cat file` | `Get-Content file`（需要整个文件内容时用 `-Raw`） |
| `grep -R pattern` | `rg pattern`（首选 ripgrep）或 `Select-String -Pattern pattern` |
| `curl ...` / `wget ...` | `curl.exe ...` / `wget.exe ...`（调用真实二进制）；处理 JSON API 推荐 `Invoke-RestMethod`（别名 `irm`） |

### 退出状态判断

- `$LASTEXITCODE`：上一个**原生可执行程序**（`git`/`adb`/`java`/`gradle` 等）的退出码，优先使用这个。
- `$?`：PowerShell 内部的成功/失败布尔值；对原生命令在不同版本/写法下行为可能有差异，不作为主要判断依据。

### 代理配置（中国大陆网络环境）

Gradle 通常读取 `~/.gradle/gradle.properties` 的 `systemProp.http.*` / `systemProp.https.*`；不要把代理硬编码进仓库。

**当前会话临时生效：**

```powershell
$env:HTTP_PROXY='http://127.0.0.1:7897'; $env:HTTPS_PROXY='http://127.0.0.1:7897'
```

**npm 项目级配置（写入当前仓库的 `.npmrc`，避免污染全局）：**

```powershell
npm config set proxy http://127.0.0.1:7897 --location=project
npm config set https-proxy http://127.0.0.1:7897 --location=project
```

**git 仓库级配置（避免污染全局配置）：**

```powershell
git config --local http.proxy http://127.0.0.1:7897
git config --local https.proxy http://127.0.0.1:7897
```

### WSL2 调用

- 如果确实需要执行 bash 命令，通过 `wsl -e bash -lc '...'` 显式调用，其余部分保持在 PowerShell 中。

## Architecture Overview

### Areas

- App 模块：`app/`
  - 入口 Activity：`app/src/main/java/com/lsl/kotlin_agent_app/MainActivity.kt`
  - 导航图：`app/src/main/res/navigation/mobile_navigation.xml`
  - 3 个模板 Fragment：`app/src/main/java/com/lsl/kotlin_agent_app/ui/*`
- 外部依赖（本地复合构建 + submodule）：`external/`
  - `external/openagentic-sdk-kotlin/`（见 `settings.gradle.kts` 的 `includeBuild(...)`）
  - `external/agent-browser-kotlin/`（见 `settings.gradle.kts` 的 `includeBuild(...)`）
- 构建与版本管理：
  - Gradle wrapper：`gradlew` / `gradlew.bat`（Gradle 8.7）
  - Version Catalog：`gradle/libs.versions.toml`（AGP / Kotlin / Jetpack 版本）
- 文档：
  - PRD：`docs/prd/`
  - 执行计划：`docs/plan/`

### Data Flow（未来目标，当前多为模板占位）

```
UI (Chat/Files/Settings)
  -> AgentService (SDK 封装)
    -> Tools (file.*, skill.*, web.*)
      -> Persistence (DataStore/Room + app internal storage)
```

### Persistence（建议约定）

- 配置与密钥：DataStore（必要时用 Jetpack Security 做加密存储）
- 文件与 skills：应用私有目录（`context.filesDir` / `context.cacheDir`）

## Runtime Config（本地调试）

- 根目录 `.env`：仅用于 Debug，已在 `.gitignore` 中忽略；Debug 构建会读取其中的默认值并注入到 App 的本地配置（用于本机测试）。
  - `OPENAI_API_KEY` / `OPENAI_BASE_URL` / `MODEL`
  - `TAVILY_URL` / `TAVILY_API_KEY`
  - `HTTP_PROXY` / `HTTPS_PROXY`
- 任何密钥不要写进代码/日志，不要提交到 git。

## Code Style & Conventions

- Language: Kotlin（当前 `gradle/libs.versions.toml` 为 Kotlin 1.9.0）
- Android: 使用 AndroidX、Navigation、ViewBinding（已开启）
- Formatting: 以 Android Studio 默认 Kotlin 格式化为准（保持现有风格）
- Naming:
  - Kotlin/Java：类 `PascalCase`，变量/函数 `camelCase`
  - 资源文件：`snake_case`（Android 常规）

## Safety & Conventions

- **禁止提交 secrets**：任何 `api_key`、第三方 tool key、证书、私钥、keystore 都不得提交到 git。
  - 替代：仅本地存放（DataStore/EncryptedSharedPreferences/环境变量），并确保日志打码。
- **本地 `.env` 仅用于 Debug**：仓库根目录 `.env` 已 gitignore；Debug 构建会读取其中的 `OPENAI_API_KEY`/`OPENAI_BASE_URL`/`MODEL` 作为默认配置注入到本机 App 存储，严禁把真实密钥提交或分发 Debug APK。
- **不要修改/提交本机路径配置**：`local.properties` 属于本机 Android SDK 路径配置。
- **谨慎删除**：涉及 `Remove-Item -Recurse -Force`、批量删除、清空目录前先确认；优先用更小范围删除。
- **避免改动生成产物**：不要手改 `build/`、`.gradle/` 等生成目录内容；需要清理用 Gradle/IDE 的 clean。
- **功能改动后默认装机验证**：每一次“成功的功能修改”（行为/界面/交互变化，且本地相关测试通过）后，默认执行 `.\gradlew.bat :app:installDebug` 安装到已连接真机/模拟器进行冒烟验证；不要等用户提醒。
- **submodule 改动不要混在本仓库提交里**：如需修改 `external/*`，优先在对应仓库提交并更新 submodule 指针；本仓库只提交 submodule 指针变化即可。
- **多行格式输出** 所有供 Agent 读取的 JSON 索引文件（如 `.countries.index.json`、`.stations.meta.json` 等）必须使用 `prettyPrint` 多行格式输出，禁止写成单行紧凑 JSON。单行大 JSON 会导致 Read 工具截断、Grep 返回整文件、上下文爆炸等连锁问题。

## Testing Strategy

- Unit tests：`.\gradlew.bat :app:testDebugUnitTest`
- Instrumentation tests：`.\gradlew.bat :app:connectedDebugAndroidTest`
- 基本原则：改了行为就补/改测试；合并前确保相关测试与 lint 通过。

## 任务提醒

每次任务执行完毕之后，使用 `apn-pushtool` 技能给手机发送一条简要消息：`xxx任务已完成`（`xxx` 为任务梗概，字数不超过 10 个字）。

任务执行期间如果出现任何需要用户确认/决策的疑问（会导致实现方向不确定或可能返工），立即使用 `apn-pushtool`（apns-tools）发一条提醒消息，简要说明疑问点并等待用户回复后再继续。

## ADB Debug（导出 sessions/events.jsonl）

> 目标：把 App 内部工作区 `.agents/sessions/<session_id>/events.jsonl` 从真机导出到本机（用于定位例如 `context_length_exceeded` 这类问题）。

### 0) 前置

- 必须是 **Debug 可调试**安装包（否则 `run-as` 会失败）。
- 先确认设备在线：`adb devices -l`（看到 `device` 才行；如果 `offline`，通常重新插拔或重启 adb：`adb kill-server ; adb start-server`）。

### 1) 定位包名与 sessions 目录

- 包名（本项目默认）：`com.lsl.kotlin_agent_app`
- 列出 sessions：
  - `adb shell run-as com.lsl.kotlin_agent_app ls -a files/.agents/sessions`

内部真实路径（仅作参考，通常不直接 `adb pull`）：`/data/user/0/com.lsl.kotlin_agent_app/files/.agents/sessions`

### 2) 快速看哪个 session 的 events.jsonl 最大

- `adb shell run-as com.lsl.kotlin_agent_app toybox du -a files/.agents/sessions | toybox grep events.jsonl | toybox sort -nr`

### 3) 导出指定 session 的 events.jsonl / meta.json（推荐）

PowerShell 下最稳妥的是用 `adb exec-out` + Windows 重定向到文件：

- 导出：
  - `cmd /c "adb exec-out run-as com.lsl.kotlin_agent_app cat files/.agents/sessions/<session_id>/events.jsonl > adb_dumps\\session-<session_id>-events.jsonl"`
  - `cmd /c "adb exec-out run-as com.lsl.kotlin_agent_app cat files/.agents/sessions/<session_id>/meta.json > adb_dumps\\session-<session_id>-meta.json"`

（可选）先建目录：`mkdir adb_dumps`。

### 4) 备注 / 常见坑

- `adb pull` 拉 `/data/user/0/...` 通常会因为权限失败；Debug 包用 `run-as` 是正道。
- 尽量用 `adb shell run-as <pkg> ls ...` 这种“直接命令”；在 `sh -c '...'` 里做复杂 `cd`/glob 时，某些设备/ROM 下工作目录行为会比较迷惑，容易跑到 `/`。

## Scope & Precedence

- 根目录 `AGENTS.md`：默认对全仓库生效。
- 子目录如新增 `AGENTS.md`：对其子树覆盖根规则。
- 同目录存在 `AGENTS.override.md` 时，优先于 `AGENTS.md`。
- 聊天中的用户显式指令优先级最高。


# Pocket Agent Workspace — 本项目的设计哲学与架构概述

## 核心理念

一切皆文件，一切皆目录。

本项目是一个运行在 Android 上的 Kotlin 原生应用，将 Unix 哲学移植到移动端。每个"应用"不是一个孤立的程序，而是一个目录加上一组操作。数据以人类可读的文件格式（JSON、.env 等）直接存储在目录中，对用户、开发者、AI agent 完全透明。

## 应用 = 目录

- 录音机是一个目录，长按弹出菜单可以录音，进入目录可以浏览所有录音文件。
- 账本是一个目录，长按弹出 UI 可以记账，账本数据以 JSON 文件存储在目录中。
- 任何"应用"的数据都可以直接通过文件管理器查看、复制、备份、同步，不需要专门的导出功能。

## UI 与 CLI 等价

所有功能必须同时提供 UI 和 CLI 两种操作方式，二者地位平等：

- UI 是给人用的视图。
- CLI 是给 AI agent 和高级用户用的接口。
- UI 能做的事情，CLI 一定能做。反之亦然。

### CLI 的实现方式

CLI 并非真正运行在 shell 中。app 内部实现了一个虚拟 shell——对外暴露的接口语法是 CLI 风格的命令，实际执行引擎是 Kotlin API 调度器。AI agent 发送类似命令行的文本指令，app 解析后路由到对应的 Kotlin 函数执行。

这样做的好处：
- 大模型天然熟悉 CLI 语法，无需额外适配。
- 不需要在 Android 上运行真实的 shell 或 PTY，安全性和复杂度都可控。
- 命令参数解析是成熟问题，用标准 CLI parser 库即可。

## .env 作为一等公民

每个目录（应用）可以有自己的 `.env` 文件，用于存储配置和密钥。

- 点击 `.env` 文件，默认弹出表单 UI（密码字段带遮罩，有输入校验），普通用户友好。
- 也可以选择以纯文本方式直接打开编辑。
- AI agent 可以通过 CLI 命令直接读写 `.env`。
- 配置天然按目录隔离，不同"应用"互不污染。

## 数据透明性原则

贯穿整个项目的设计原则：

- 数据格式是人类可读的（JSON、.env、纯文本）。
- 数据存储是透明的（就在文件系统的目录里）。
- 操作方式是多样但等价的（UI、CLI、直接编辑文件，三条路通向同一个地方）。
- 备份 = 复制目录，同步 = rsync，版本管理 = git，搜索 = grep。

## 目标平台

- Android，目标设备为华为 Nova 9，API 30-31。
- Kotlin 原生开发。
