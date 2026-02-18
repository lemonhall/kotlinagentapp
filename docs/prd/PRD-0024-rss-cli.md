# PRD-0024：RSS/Atom 订阅与抓取（RSS CLI / Pseudo Terminal Commands）

日期：2026-02-18  
定位：在 `terminal_exec` 的“白名单 pseudo CLI（无 shell / 无外部进程）”框架下，提供一个可复用的 RSS/Atom 订阅与抓取基础能力（`rss` CLI），用于后续把各大新闻网站/博客的 feed 接入到 Agent 的工具箱中；本 PRD **先锁口径**（协议、数据模型、输出契约、落盘与护栏），实现按 v24 计划另行落地。

## Vision

让用户与 Agent 都可以在 App 内仅通过 `terminal_exec`：

- 订阅/管理 RSS/Atom feed（add/list/remove）
- 拉取指定 feed 最新条目（fetch）
- 以“摘要（stdout/result）+ 大输出落盘（artifacts）”的方式稳定消费内容
- 在中国大陆网络环境下可通过系统代理/网络栈正常访问（由 App/系统层负责；命令不硬编码代理）

并且：

- **无外部进程**：仅 App 内置 Kotlin + HTTP（OkHttp）+ RSS 解析库（建议 ROME）
- **可审计**：每次调用由 `terminal_exec` 审计落盘（工具已自带）
- **输出可控**：默认只输出摘要；完整条目列表必须落盘为 artifacts 引用，避免 stdout/stderr 截断误判
- **可回归**：协议/错误码/落盘结构写死，后续按 `paw-cli-add-workflow` 做 TDD 落地

## Background

RSS/Atom 是“站点提供的结构化更新流”，相比网页抓取更稳定、成本更低、合法边界更清晰。现实约束：

- 不是所有网站都有公开 feed；部分 feed 仅包含摘要，不包含全文（例如华盛顿邮报仅 `<description>` 摘要，不提供 `<content:encoded>`）
- feed 更新频率与条目数量不稳定；必须有“默认摘要 + 可选/强制落盘”的输出策略
- 网络失败、429/503、以及 XML/编码异常是常态；错误码必须可解释

## Non-Goals（v24）

- 不做：网页全文抓取/可读性抽取（readability）/绕过付费墙
- 不做：站点账号登录态、Cookie 管理
- 不做：内容推荐/总结/观点生成（只提供数据抓取与落盘）
- 不做：并行批量抓取与复杂调度（后续另开 PRD；v24 先打通协议与单次抓取闭环）

## Dependencies

- HTTP 客户端：OkHttp（Android 生态）
- RSS/Atom 解析库（实现建议）：
  - 首选：ROME（`com.rometools:rome`，成熟、覆盖 RSS+Atom）
- 持久化落盘：应用私有目录 `.agents/workspace/rss/`（JSON/JSONL）

## Project Rules（实现必须遵守：来自 paw-cli-add-workflow）

- `TerminalCommands.kt` 只能做“命令注册表/默认 registry 构建”，禁止堆实现逻辑。
- 顶层命令必须独立目录：
  - `rss`：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/rss/*`
- `TerminalExecTool` 的 registry 初始化逻辑不改；只在 `TerminalCommands.defaultRegistry(...)` 注册新增命令。

## Workspace（落盘结构）

默认工作区（实现必须使用固定结构）：

- `.agents/workspace/rss/subscriptions.json`：订阅列表（数组）
- `.agents/workspace/rss/fetch_state.json`：每个 feed 的抓取状态（etag/last_modified/last_fetch_ms 等）
- `.agents/artifacts/rss/*`：`--out` 产物（feeds/items/raw xml 等）

订阅项（JSON，最小字段）：

- `name`：订阅名（唯一，建议小写+中划线）
- `url`：feed URL（`http/https`）
- `created_at_ms`
- `updated_at_ms`

抓取状态项（JSON，最小字段）：

- `name`
- `etag`（可空）
- `last_modified`（可空，原样字符串）
- `last_fetch_ms`（可空）
- `last_status`（可空：200/304/429/…）

## Command Set（v24 目标命令协议）

统一输出契约：

- `exit_code`：0 成功；非 0 失败
- `stdout`：人类可读摘要（不超过 16k）
- `result`：结构化 JSON（稳定字段）
- `artifacts[]`：大输出落盘引用（path/mime/description）
- 失败必须提供：`error_code` + `error_message`

统一 guardrails：

- 仅允许 `http://` / `https://` URL；拒绝 `file://` 等 scheme（`InvalidArgs`）
- 默认超时（实现建议 15s）+ 最大响应体限制（实现建议 2MB，防止炸弹）
- 大输出必须走 `--out` 落盘，stdout/result 只给摘要
- 所有 `--out` 路径必须在 `.agents/` 内；拒绝绝对路径与 `..`（`PathEscapesAgentsRoot` / `InvalidArgs`）

### 1) `rss add`

- 命令：
  - `rss add --name wapo-politics --url https://www.washingtonpost.com/arcio/rss/category/politics/`
- 行为：
  - 写入/更新 `.agents/workspace/rss/subscriptions.json`
  - name 已存在：覆盖 url 并更新 `updated_at_ms`
- result（最小字段）：
  - `ok`
  - `command: "rss add"`
  - `name`
  - `url`

### 2) `rss list`（默认摘要；完整清单用 `--out`）

- 命令：
  - `rss list --max 50`
  - `rss list --out artifacts/rss/subscriptions.json`
- 默认行为：
  - 无 `--out`：`result.items` 最多 `<= max`，且只包含 `name/url/updated_at_ms`
  - 有 `--out`：落盘完整订阅数组，并返回 artifact

### 3) `rss remove`

- 命令：
  - `rss remove --name wapo-politics`
- 行为：
  - 从订阅列表删除；若不存在，返回 `NotFound`
- result（最小字段）：
  - `ok`
  - `command: "rss remove"`
  - `name`

### 4) `rss fetch`（抓取并解析；默认摘要；完整条目用 `--out`）

- 命令：
  - `rss fetch --name wapo-politics --max-items 20`
  - `rss fetch --name wapo-politics --max-items 100 --out artifacts/rss/wapo-politics-items.json`
  - （可选）直接 URL 抓取（不依赖订阅表）：
    - `rss fetch --url https://www.washingtonpost.com/arcio/rss/category/politics/ --max-items 20`
- 行为：
  - 使用缓存头（If-None-Match/If-Modified-Since）减少流量（实现阶段落地）
  - 解析 RSS/Atom，产出统一条目模型（title/link/guid/author/published_at/summary）
  - 无 `--out`：只返回 `<= max-items` 的摘要条目（标题+时间+链接）
  - 有 `--out`：落盘完整条目数组并返回 artifact
- result（最小字段）：
  - `ok`
  - `command: "rss fetch"`
  - `name`（若传入 name）
  - `url`
  - `count_total`
  - `count_emitted`
  - `out`（若传入）

## Normalized Item Model（统一条目模型）

条目（JSON，最小字段）：

- `title`（可空）
- `link`（可空）
- `guid`（可空）
- `author`（可空）
- `published_at`（可空；尽量输出 RFC3339；若无法解析则原样字符串）
- `summary`（可空；通常来自 `<description>` 或 Atom `<summary>`）

## Error Codes（统一口径）

- `InvalidArgs`：参数缺失/格式错误/不支持的 URL scheme
- `NotFound`：订阅不存在（remove/fetch by name）
- `NetworkError`：网络/超时/无法解析
- `HttpError`：非 2xx/304 HTTP（包含 status）
- `RateLimited`：429（若响应包含 `Retry-After` 且可解析为秒数，则必须返回 `retry_after_ms`，否则允许省略）
- `ParseError`：XML/RSS/Atom 解析失败
- `OutRequired`：该命令要求 `--out`（如未来加入“大清单强制落盘”的子命令）
- `PathEscapesAgentsRoot`：`--out` 路径越界（含 `..` 或绝对路径）

## Acceptance（v24 交付口径：文档阶段）

> v24 为文档阶段：锁定协议与验收口径；不写实现代码。

1. `docs/prd/PRD-0024-rss-cli.md` 存在，且包含 Req IDs、Non-Goals、Acceptance、Workspace、Command Set、Error Codes。  
2. `docs/plan/v24-rss-cli.md` 存在，且包含可执行 Steps 与 Files 规划。  
3. 文档中的 DoD/Acceptance 全部可二元判定（pass/fail），无模糊词。  
4. 提交与推送：`git status --porcelain=v1` 为空。  

## Requirements（Req IDs）

### A. 命令面（订阅 + 抓取）

- REQ-0024-001：新增 `rss add`，写入/更新订阅列表到 `.agents/workspace/rss/subscriptions.json`。
- REQ-0024-002：新增 `rss list`，默认摘要输出；支持 `--out` 落盘完整订阅列表并返回 artifact。
- REQ-0024-003：新增 `rss remove`，支持按 name 删除；不存在返回 `NotFound`。
- REQ-0024-004：新增 `rss fetch`，支持按 name 或 url 抓取并解析 RSS/Atom；默认摘要；支持 `--out` 落盘完整条目并返回 artifact。
- REQ-0024-005：统一条目模型（title/link/guid/author/published_at/summary）并输出到 `result` 或 `--out` 产物。

### B. 安全/工程

- REQ-0024-010：实现必须放在独立目录 `commands/rss/*`；`TerminalCommands.kt` 只做注册表。
- REQ-0024-011：路径必须受 `.agents` 根目录约束，拒绝绝对路径与 `..`。
- REQ-0024-012：大输出不得直接塞 stdout/result；必须 `--out` 落盘并通过 artifacts 引用。
- REQ-0024-013：错误码必须可解释且稳定（见 Error Codes），429 口径为 `RateLimited`（若响应包含 `Retry-After` 且可解析为秒数，则必须返回 `retry_after_ms`，否则允许省略）。
