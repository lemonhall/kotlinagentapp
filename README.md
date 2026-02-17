# Kotlin Agent App

Android 端的聊天型 Agent 应用工程（起步项目）。目标是逐步接入 `openagentic-sdk-kotlin` / `agent-browser-kotlin`，实现「对话 + 工具调用 + Skills + App 内工作区（`.agents/*`）」的一体化体验，并为后续 WebView 自动化预留扩展点。

An Android chat-style Agent app starter. The goal is to integrate `openagentic-sdk-kotlin` / `agent-browser-kotlin` and deliver an end-to-end experience: chat + tool calls + skills + an in-app workspace (`.agents/*`), with room for future WebView automation.

## Status / 现状

- 基于 Android Studio Bottom Navigation 模板（Fragment + ViewBinding + Navigation）。
- 需求与执行计划见：`docs/prd/`、`docs/plan/`。

## Roadmap (high level) / 功能路线图（摘要）

- Chat：对话 + 流式回复 + 工具调用轨迹
- Tools：内置 `file.*` / `skill.*` 等，并支持配置第三方工具 key
- Skills：对话式安装/管理 skills，查看已安装 skill 列表
- Files：管理应用内部 `.agents/*` 工作区（例如浏览/编辑 `.agents/skills/<name>/SKILL.md`）
- Settings：配置 `base_url`、`api_key`、默认模型、工具 key
- Future：WebView + Agent 操作网页

## Prerequisites / 开发环境

- Android Studio (latest stable recommended)
- JDK 17+
- Android SDK Platform 35（当前 `compileSdk=35`）
- Windows 11 + PowerShell（示例命令以 PowerShell 为主；连续执行用 `;`）

## Quickstart (PowerShell) / 快速开始

- 初始化 submodule：`git submodule update --init --recursive`
- 构建 Debug APK：`.\gradlew.bat :app:assembleDebug`
- 运行单元测试：`.\gradlew.bat :app:testDebugUnitTest`
- Lint：`.\gradlew.bat :app:lintDebug`

> 安装到真机/模拟器：通常通过 Android Studio；如需命令行安装：`.\gradlew.bat :app:installDebug`（需要已连接设备/已启动模拟器）。

## Local .env (Debug only) / 本地 .env（仅 Debug）

仓库根目录支持放置本地 `.env`（已在 `.gitignore` 中忽略）。Debug 构建会读取其中的默认值并注入到 App 的本地配置（仅用于本机测试）。

Example (do not commit real secrets) / 示例（不要提交真实密钥）：

```env
OPENAI_API_KEY="..."
OPENAI_BASE_URL="https://example.com/v1"
MODEL="gpt-5.2"
```

## Repo layout / 目录结构

- `app/`：Android 应用模块（当前为模板代码起步）
- `external/openagentic-sdk-kotlin/`：SDK（git submodule；通过 composite build 接入）
- `external/agent-browser-kotlin/`：本地 browser agent（git submodule；通过 composite build 接入）
- `settings.gradle.kts`：`includeBuild("external/...")`（复合构建入口）
- `gradle/libs.versions.toml`：版本目录（Version Catalog）
- `docs/prd/`：需求文档（PRD）
- `docs/plan/`：执行计划（按 vN 管理）

## Security notes / 安全提示

- 不要把任何 `api_key` / tool keys / 证书 / 私钥 / keystore 写进代码或提交到 git。
- `local.properties` 属于本机配置（包含 SDK 路径），不应提交或共享。
