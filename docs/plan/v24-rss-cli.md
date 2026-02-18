# v24 Plan：RSS CLI（订阅 + 抓取 / 文档锁口径）

## Goal

把“RSS/Atom 订阅与抓取”能力用 `terminal_exec` 的白名单命令 `rss` 表达出来，并把落盘结构、输出契约与安全护栏写死在验收口径里；v24 **只落文档，不写实现**。

## PRD Trace

- PRD-0024：REQ-0024-001 / REQ-0024-002 / REQ-0024-003 / REQ-0024-004 / REQ-0024-005
- PRD-0024：REQ-0024-010 / REQ-0024-011 / REQ-0024-012 / REQ-0024-013

## Scope

做（v24：文档阶段）：
- 锁定命令集合与 argv 约定（`rss add/list/remove/fetch`）
- 锁定工作区落盘结构（`.agents/workspace/rss/*`）
- 锁定输出契约（stdout/result/artifacts/exit_code/error_code）
- 锁定 guardrails：
  - 只允许 `http/https` URL（拒绝 `file://`）
  - 大输出必须 `--out` 落盘 + artifacts 引用
  - `--out` 路径必须受 `.agents` 根目录约束（拒绝绝对路径与 `..`）
  - 429 输出 `RateLimited`；若响应包含 `Retry-After` 且可解析为秒数，则必须返回 `retry_after_ms`，否则允许省略

不做（v24）：
- 任何代码实现（命令、单测、builtin skill 安装）
- 网页全文抓取/readability 抽取
- 批量抓取调度与并行（后续另开版本）

## Acceptance（硬口径，面向后续实现）

1. `rss add` 会写入/更新 `.agents/workspace/rss/subscriptions.json`（后续实现用单测断言文件存在且包含 name/url）。  
2. `rss list` 默认只输出摘要；提供 `--out` 时落盘完整 JSON 并在 `artifacts[]` 返回 `.agents/<out_path>`。  
3. `rss remove --name X`：X 不存在必须失败（`NotFound`）。  
4. `rss fetch`：无 `--out` 只输出 `<= max-items` 摘要；有 `--out` 落盘完整条目数组并返回 artifact。  
5. URL scheme：仅允许 `http/https`；`file://` 必须失败（`InvalidArgs`）。  
6. 路径约束：所有 `--out` 必须在 `.agents/` 内，拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）。  
7. 429：必须输出 `error_code="RateLimited"`；若响应包含 `Retry-After` 且可解析为秒数，则必须返回 `retry_after_ms`，否则允许省略。  
8. `.\gradlew.bat :app:testDebugUnitTest` exit code=0（后续实现时必须补齐覆盖用例）。  

反作弊条款（必须）：
- 禁止“只注册命令但返回假数据”就宣称完成：后续单测必须断言真实落盘文件存在、条目字段可解析，并用 fake HTTP 断言请求确实被发出且响应被解析（覆盖 ParseError/HttpError/RateLimited）。

## Files（规划：遵守 paw-cli-add-workflow；v24 不落地代码）

> 下列为后续实现阶段预计会改动/新增的路径清单（v24 只写计划，不实际创建）。

- 注册表（只注册，不写实现）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（拆分目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/rss/RssCommand.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/rss/*`（HTTP + 解析 + 模型 + 存储）
- 单测（Robolectric）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- 内置 skill（让 Agent 能按说明书调用）：
  - `app/src/main/assets/builtin_skills/rss-cli/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`
- 站点 feed 指引（纯文本 skill）：
  - `app/src/main/assets/builtin_skills/wapo-rss/SKILL.md`

## Steps（Strict）

> v24 只做文档闭环，因此步骤只覆盖 Analysis/Design/Plan + Gate，不进入实现。

1) Analysis：确定 RSS/Atom 覆盖范围、常见字段、失败场景与 guardrails  
2) Design：锁定命令协议、错误码与输出契约（何时必须 `--out`）  
3) Plan：写清 Files / 验收口径 / 未来测试点（InvalidArgs/NotFound/HttpError/RateLimited/ParseError/路径越界）  
4) DoD Gate：检查 Acceptance 全部可二元判定且有明确验证命令/断言口径  
5) Doc QA Gate：术语一致（rss/atom/feed/subscription/out/artifacts/workspace），Req IDs 追溯不断链  
6) Ship（文档）：`git add -A ; git commit -m "v24: doc: rss cli spec" ; git push`  

## Risks

- 输出过大截断：部分 feed 条目很多/字段很长 → 必须默认摘要 + `--out` 落盘。  
- 解析兼容性：RSS/Atom 变体多 → 实现阶段优先选成熟解析库（ROME）。  
- 合法边界：RSS feed 仅作为更新索引；不做全文抓取与绕过订阅。  
