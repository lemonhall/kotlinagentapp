# PRD-0008：Local-first Automation（定时 + 分享触发，手机即工作台）

日期：2026-02-17  
定位：**不需要自建服务端**（仅调用 LLM API），只用一台 Android 手机即可获得「Skills + 自动化流水线」能力。

## Vision

把本 App 做成“手机上的个人工作台”：用户通过**定时（Schedule）**与**分享（Share Intent）**两类触发器，启动一条可审计、可复跑、可导出的自动化流水线（Automation），产出结构化交付物（Markdown/JSON/文件）并落盘到 App 内 `.agents/*` 工作区。

核心差异化一句话：**同样是“能做事的 AI”，我们不要求用户额外部署任何常驻服务端/小主机；一个手机就够。**

## Background / Problem

- 大量“自动化 + AI”产品要么依赖云端编排（如 IFTTT 类连接器），要么需要自托管工作台/sidecar（需要服务器/小主机常驻）。
- 你（柠檬叔）的目标是：在中国大陆网络环境与移动端约束下，仍能稳定获得“研究/产出/归档/提醒”的闭环，而且**数据与工作区尽量本地化**、**可复用**、**可回放**。

## Target Users

- P1：个人开发者/IT 工作者（手机也要能“收集→分析→产出→归档”）。
- P2：不想自建服务端的用户（只愿意配置 `base_url/api_key/model`）。

## Non-Goals（本轮不做）

- 不做跨设备云同步/团队协作/账号体系。
- 不做后台常驻的“全能监听器”（通知、无障碍、全局剪贴板持续监听等）作为 v1 依赖（权限与误触风险高）。
- 不做“跨 App 的强 RPA 操作”（点按/滑动/自动填写）作为 v1 交付；网页能力仍以受控 WebView 工具为主线。

## Constraints & Principles

- **No Server**：除 LLM API 外，不依赖自建后端；自动化编排、状态、日志、产物都在手机本地。
- **Local-first Workspace**：所有 Automation 定义、输入、运行日志与产物落在 `context.filesDir/.agents/*`（可导出）。
- **Auditability（可审计）**：每次 run 必须生成最小可回放证据（输入、步骤、工具调用摘要、产物路径、错误信息）。
- **User Control（可控）**：长任务可取消；高风险动作必须显式确认（删除文件、对外发送、执行本地脚本等）。
- **Battery & Background Friendly**：遵循 Android 后台限制；定时任务用 WorkManager/Alarm 语义，避免“常驻服务”。

## Core Concepts

- **Automation（自动化配方）**：一个可复用的流水线定义（触发器 + 步骤 + 产物 + 通知）。
- **Trigger（触发器）**：v1 仅两类：
  - Schedule：定时/周期触发（WorkManager）。
  - Share：从任意 App “分享 → 本 App”触发（Share Intent），把内容进入 “Inbox” 并可立即/稍后跑。
- **Run（一次执行）**：Automation 的一次实例化运行，带 run_id、状态、日志与产物。
- **Artifact（产物）**：Markdown 报告、对比表、TODO 清单、JSON、下载文件等，统一落盘并可从 Files 浏览。
- **Skill（能力块）**：`.agents/skills/<name>/SKILL.md` 形式的可描述能力；Automation 步骤可引用 Skill 或内置工具（file/webview 等）。

## Differentiation（相对竞品）

### D1：一台手机即可“能跑的自动化”

- 竞品常见形态：云端连接器（依赖账号与云），或自托管工作台（依赖服务器/小主机常驻）。
- 本 App：**本地编排 + 本地工作区 + 可导出证据链**，用户只需配置 LLM API。

### D2：自动化结果“落盘为资产”，而不是一次性对话

- 每次 Run 都生成：输入快照 + 步骤摘要 + 产物文件 + 错误与重试点。
- 用户可以把一条成功 Run 复用成模板（同配方不同输入），形成个人 SOP。

### D3：从“研究/产出”自然过渡到“流程自动化”

- Share Trigger 把“看到一条链接/内容 → 立刻丢给工作台处理”变成肌肉记忆。
- Schedule Trigger 把“每日/每周固定产出”（简报、周报、待办清理）变成无人值守执行。

### D4（Future）：本地可编程运行时（Node-like）

- 方向：在不引入服务端的前提下，允许 Automation 的某些步骤执行本地脚本/模块（受权限与沙箱约束，默认关闭），把“工具调用”扩展为“本地编码生产力”。

## UX Flows（v1）

### Flow A：定时触发（Schedule）

1. 用户在 Automation 页创建/选择一个“每日例行”模板（例如“每日简报/每日待办清理”）。
2. 配置：运行时间、时区、是否 Wi‑Fi only、失败重试策略。
3. 到点后后台触发 Run：执行 → 产物落盘 → 通知摘要（可点进查看产物）。

### Flow B：分享触发（Share）

1. 用户在任意 App 选中文本/链接 → 系统“分享” → 选择本 App。
2. App 进入“Inbox”：展示输入预览与可选配方（最近使用/推荐）。
3. 用户点“运行”或“加入队列”：Run 执行 → 产物落盘 → 通知/回到 App 查看。

## Requirements（Req IDs）

### A. Automation 定义与落盘

- REQ-0008-001：Automation 定义落盘到 `filesDir/.agents/automations/<automation_id>/automation.json`。
  - 包含：name、trigger(s)、steps（引用 skill/tool）、output_spec、notification_spec、enabled。
- REQ-0008-002：支持列出/启用/禁用/复制 Automation（复制产生新 id）。

### B. 触发器

- REQ-0008-010：Schedule 触发器（最小可用）。
  - 支持：每天固定时间（v1），失败重试（指数退避或固定次数）。
  - Acceptance：到点后产生 Run，Run 记录可在 UI 中看到。
- REQ-0008-020：Share Intent 触发器（最小可用）。
  - 接收：text/plain（包含纯文本与 URL）。
  - Acceptance：分享后进入 Inbox，用户可选择配方并成功产生 Run。

### C. Run 执行与可控性

- REQ-0008-030：每次 Run 生成 `run.json` 与 `events.jsonl`（或同等结构化日志）。
- REQ-0008-031：Run 可取消；取消后记录状态与已产生产物路径。
- REQ-0008-032：错误可重试（从头重跑 v1 即可；未来可做 step-level retry）。

### D. 产物（Artifacts）

- REQ-0008-040：产物落盘到 `filesDir/.agents/artifacts/<run_id>/...`，并在 Files 页可浏览/分享导出。
- REQ-0008-041：至少支持一种“研究/产出型”产物模板：`report.md`（包含摘要、要点、下一步）。

### E. 通知与回到现场

- REQ-0008-050：Run 完成后发送本地通知，包含 1 行摘要与入口（点按打开产物/Run 详情）。

## Data & Storage（建议约定）

- `.agents/automations/<automation_id>/automation.json`
- `.agents/runs/<run_id>/run.json`
- `.agents/runs/<run_id>/events.jsonl`
- `.agents/artifacts/<run_id>/*`
- `.agents/inbox/<inbox_id>.json`（Share 输入快照，避免丢失）

## Safety / Permissions

- v1 权限最小化：Share Intent 不需要敏感权限；Schedule 走系统调度。
- 所有“高风险动作”（删除文件、对外发送、执行本地脚本/模块、访问账号数据）必须二次确认，并默认关闭相关能力。

## Milestones（建议）

- v11：
  - M1：Automation 定义落盘 + 列表页（REQ-0008-001/002）
  - M2：Share → Inbox → Run（REQ-0008-020/030/040/050）
  - M3：Schedule → Run（REQ-0008-010/030/050）
  - M4：最小模板 2 个（“链接→摘要归档”“每日简报”）

## Open Questions（待确认）

1. Run 日志格式：沿用 SDK 的 `events.jsonl` 还是独立定义（建议复用/兼容）。
2. 分享触发后的默认策略：立即运行还是先进 Inbox（建议先进 Inbox，用户可一键运行）。
3. “本地可编程运行时”的最小安全沙箱边界与默认开关策略（未来单独 PRD）。

