# v2 Plan：以 composite build 引入 openagentic-sdk-kotlin

## Goal

把 `openagentic-sdk-kotlin` 以 **composite build** 方式引入本仓库，使 app 模块能够通过 `implementation(...)` 编译期引用 SDK API（不要求发版、不要求发布到 Maven）。

## PRD Trace

- REQ-0001-040

## Scope

做：
- 将 SDK 作为 `external/openagentic-sdk-kotlin/` 子仓库（建议 git submodule；本地也可直接 clone）
- 根 `settings.gradle.kts` 增加 `includeBuild(...)`（composite build）
- app 依赖 SDK（具体坐标/模块以 SDK 实际结构为准）

不做：
- 不调整 SDK 的内部实现（除非为“能被 composite build 正常解析/编译”的必要修复）

## Acceptance（硬口径）

1. `.\gradlew.bat :app:assembleDebug` exit code=0
2. `.\gradlew.bat :app:testDebugUnitTest` exit code=0
3. `app` 中新增一个最小编译验证文件（例如 `AgentService`）能 import 到 SDK 的类型（不允许只加依赖但不引用）

## Steps（Strict）

1) 拉取 SDK 到 `external/openagentic-sdk-kotlin/`（submodule 或 clone）
2) 读取 SDK 的 `settings.gradle(.kts)` / `build.gradle(.kts)`，确认 group/artifact 或 module 名称
3) 修改本项目 `settings.gradle.kts` 增加 `includeBuild("external/openagentic-sdk-kotlin")`
4) 在 `app/build.gradle.kts` 添加依赖到 SDK 模块/坐标
5) 新增最小编译引用点（`AgentService`）并跑 build/test

## Risks

- SDK 使用的 Gradle/AGP/Kotlin 版本与本工程不一致：优先用 composite build 的版本对齐策略（必要时再调整）。

