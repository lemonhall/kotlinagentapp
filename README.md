# Kotlin Agent App

Android 端的聊天型 Agent 应用（起步工程），目标是接入 `openagentic-sdk-kotlin`，实现「对话 + 工具 + Skills + App 内文件系统」的一体化体验，并为后续 WebView 自动化预留扩展点。

## 现状

- 当前代码来自 Android Studio 的 Bottom Navigation（Fragment + ViewBinding + Navigation）模板。
- 需求与规划见：`docs/prd/PRD-0001-kotlin-agent-app.md`、`docs/plan/`。

## 功能路线图（摘要）

- Chat：与大模型对话，展示流式回复与工具调用轨迹
- Tools：内置 `file.*` / `skill.*`，并可配置第三方工具 key
- Skills：对话式安装/管理 skills，查看已安装 skill 列表
- Files：管理应用内部 `.agents/*` 工作区（可浏览/编辑 `.agents/skills/<name>/SKILL.md`）
- Settings：配置 `base_url`、`api_key`、默认模型、工具 key
- Future：WebView + Agent 操作网页

## 开发环境

- Android Studio（建议最新稳定版）
- JDK 17+（Android Gradle Plugin 8.x 推荐）
- Android SDK Platform 35（当前 `compileSdk=35`）
- Windows 11 + PowerShell（本仓库命令示例以 PowerShell 为主）

## 快速开始（PowerShell）

- 构建 Debug APK：`.\gradlew.bat :app:assembleDebug`
- 运行单元测试：`.\gradlew.bat :app:testDebugUnitTest`
- Lint：`.\gradlew.bat :app:lintDebug`

> 运行到真机/模拟器通常通过 Android Studio；如需命令行安装：`.\gradlew.bat :app:installDebug`（需要已连接设备/已启动模拟器）。

## 本地 .env（仅 Debug：免手填配置）

仓库根目录支持放置本地 `.env`（已在 `.gitignore` 中忽略），Debug 构建会把其中的默认值注入到 App 的本地配置（仅用于本机测试）。

示例（不要提交真实密钥）：

```env
OPENAI_API_KEY="..."
OPENAI_BASE_URL="https://example.com/v1"
MODEL="gpt-5.2"
```

## 目录结构（当前）

- `app/`：Android 应用模块（当前为模板代码）
- `external/openagentic-sdk-kotlin/`：SDK（git submodule，用于本地开发；并通过 composite build 接入）
- `gradle/libs.versions.toml`：版本目录（Version Catalog）
- `docs/prd/PRD-0001-kotlin-agent-app.md`：产品需求文档
- `docs/plan/`：按 vN 管理的执行计划

## 安全提示

- 不要把任何 `api_key` / tool keys 写进代码或提交到 git。
- `local.properties` 属于本机配置（包含 SDK 路径），不应提交或共享。
