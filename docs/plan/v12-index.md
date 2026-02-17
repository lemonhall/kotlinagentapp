# v12 Index：apn-pushtool 内置移植（Pseudo Terminal 命令）

日期：2026-02-17（计划于 2026-02-18 开始执行）

## Vision（引用）

- PRD：`docs/prd/PRD-0011-pseudo-terminal-skill-runtime.md`
- 本轮目标：把桌面端 `apn-pushtool` 的“CLI 形态”移植为 App 内置能力（Kotlin 实现），通过 `terminal_exec` 提供 `apn ...` 白名单命令，并配套内置 skill 文档与审计/安全策略，形成最小闭环。

## Milestones

### M1：`apn doctor/send` 最小闭环（Kotlin）

- PRD Trace：
  - PRD-0011：REQ-0011-001 / REQ-0011-002 / REQ-0011-010
  - 本轮新增（v12 计划内定义）：REQ-0012-001 ~ REQ-0012-010（见 plan 文档）
- DoD（硬口径）：
  - 新增 `terminal_exec` 子命令 `apn doctor`：读取指定 config 文件，输出“mask 后的配置摘要”；
  - 新增 `terminal_exec` 子命令 `apn send`：要求显式 `--confirm` 才允许真实推送；
  - 推送实现使用 Kotlin（OkHttp + ES256 JWT），不引入/执行 Python；
  - 审计落盘不包含 `.p8` 私钥内容与 device token 全量（必须打码）；
  - `.\gradlew.bat :app:testDebugUnitTest` exit code=0
- Plan：`docs/plan/v12-port-apn-pushtool.md`

## Plan Index

- `docs/plan/v12-port-apn-pushtool.md`

## ECN Index

- （如需调整“terminal 默认无网络”口径：执行时新增 ECN）

