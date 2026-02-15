# Agent Notes (kotlin-agent-app)

## Project Overview

这是一个 Android 应用工程（Android Studio Bottom Navigation 模板起步），目标是逐步接入 `openagentic-sdk-kotlin`，实现聊天型 Agent：对话、工具调用、Skill 管理、应用内文件系统管理，并预留 WebView 自动化能力。

## Quick Commands (PowerShell)

- 前置：安装 Android SDK Platform 35（当前 `compileSdk=35`）
- Install deps / Sync: `.\gradlew.bat --version`（确认 wrapper 可用；依赖会在首次构建自动下载）
- Build (debug): `.\gradlew.bat :app:assembleDebug`
- Install to device (debug): `.\gradlew.bat :app:installDebug`（需要 `adb devices` 可看到真机/模拟器）
- Lint: `.\gradlew.bat :app:lintDebug`
- Test (unit): `.\gradlew.bat :app:testDebugUnitTest`
- Test (instrumented): `.\gradlew.bat :app:connectedDebugAndroidTest`（需要已启动模拟器或连接真机）

> 说明：本仓库默认使用 PowerShell；连续执行命令用 `;` 分隔（例如 `cd app ; ls`），不要用 `&&`。

## Architecture Overview

### Areas

- App 模块：`app/`
  - 入口 Activity：`app/src/main/java/com/lsl/kotlin_agent_app/MainActivity.kt`
  - 导航图：`app/src/main/res/navigation/mobile_navigation.xml`
  - 3 个模板 Fragment：`app/src/main/java/com/lsl/kotlin_agent_app/ui/*`
- 构建与版本管理：
  - Gradle wrapper：`gradlew` / `gradlew.bat`（Gradle 8.7）
  - Version Catalog：`gradle/libs.versions.toml`（AGP / Kotlin / Jetpack 版本）

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

### Proxy（中国大陆网络环境）

- Gradle 通常读取 `~/.gradle/gradle.properties` 的 `systemProp.http.*` / `systemProp.https.*`；不要把代理硬编码进仓库。
- 如需临时代理，优先在当前会话设置环境变量或在用户级 Gradle 配置设置。

## Testing Strategy

- Unit tests：`.\gradlew.bat :app:testDebugUnitTest`
- Instrumentation tests：`.\gradlew.bat :app:connectedDebugAndroidTest`
- 基本原则：改了行为就补/改测试；合并前确保相关测试与 lint 通过。

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
