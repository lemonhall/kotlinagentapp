# PRD-0011：Pseudo Terminal（白名单 CLI）Skill Runtime

日期：2026-02-17  
定位：**上架友好（No dynamic code exec）**，用“伪终端/伪 CLI”的交互形式承载一批强依赖 CLI 的 Skills，但底层不提供 bash，也不执行外部二进制。

## Vision

让 App 内的 Agent 只通过一个工具（`terminal.exec`）就能按 `SKILL.md` 的“命令式说明书”逐行执行能力；这些能力以“CLI 命令”的形式呈现给 Agent，但实际实现是 App 内置的 Kotlin/Java 代码与库（如 PDFBox/POI 等），从而：

- 保留“CLI 友好技能”的迁移体验（低成本移植）
- 满足上架与安全要求（无外部进程、无动态下载/执行）
- 跨平台可迁移（Android/iOS 共享同一套命令协议与 SKILL 文档）

## Non-Goals

- 不提供 `bash`/`sh`/管道/重定向/环境变量等完整 shell 语义。
- 不实现 `cd/pwd/ls/cat/grep` 等通用 Linux 命令（已有规范化的 file tools：READ/WRITE/EDIT/LIST/MKDIR/GREP/GLOB）。
- 不执行任意脚本（`.py/.js/.sh`）或外部二进制（`ProcessBuilder` 禁用）。
- 不提供“在线安装 CLI/插件”或任何动态能力分发（上架风险高）。

## Core Idea

把“CLI”降级成**命令协议（Command Protocol）**：

- 对 Agent 暴露的是“看起来像 CLI 的命令行字符串”
- 对 App 来说就是“解析字符串 → 命令路由 → 调用内置实现 → 返回结构化结果 + stdout/stderr 文本”

## One Tool Surface

对 LLM/Agent 暴露单一工具：

- `terminal.exec`
  - 输入：
    - `command: string`（单行命令，不允许换行/多命令串联）
    - `stdin: string?`（可选，纯文本输入）
    - `timeout_ms: number?`（可选，上限由 App 再裁剪）
  - 输出（结构化 + 文本）：
    - `exit_code: 0|nonzero`
    - `stdout: string`（可截断）
    - `stderr: string`（可截断）
    - `result: object?`（命令自定义结构化结果）
    - `artifacts: [{path, mime, description}]?`（可选：产物引用，便于自动化落盘与 UI 展示）

> 注：终端 UI 只是“展示形式”，对 Agent 而言 `terminal.exec` 是唯一入口。
>
> 实现提示（v1）：由于 OpenAI function name 约束（不允许 `.`），App 对 LLM 暴露的实际工具名使用 `terminal_exec`（语义等价于 `terminal.exec`）。

## Command Registry（白名单命令）

### 原则

- 白名单注册：未注册命令一律拒绝（`exit_code!=0` + `UnknownCommand`）。
- 参数严格解析：拒绝歧义与“自由文本拼接”（减少 prompt 注入与误用）。
- 每个命令必须声明：
  - `capabilities`（读文件/写文件/网络/高风险）
  - `resource_limits`（最大输入大小、最大输出大小、最大耗时）
  - `auditing`（必写 run log 的字段）

### 参数解析建议

- 使用稳定的参数解析库（如 picocli 一类的“CLI args → 结构体”），但执行逻辑完全由 App 控制。
- 命令行语法仅支持：
  - `cmd subcmd? --flag value --bool-flag`
  - 引号支持（最小实现：双引号包裹含空格参数）
  - 不支持：pipe、`;`、`&&`、`$()`、glob 展开（交给 file tools）。

## 与现有 file tools 的关系（关键约束）

- `terminal.exec` **不负责**通用文件系统操作；所有“读写编辑列举搜索创建目录”等由现有 tool 完成。
- CLI 命令输入输出建议只处理：
  - “把某个文件解析成文本/结构化数据”
  - “把一段文本转换为另一种格式”
  - “对结构化输入进行归一化/提取/对齐”
- 命令需要访问文件时，采用**显式路径参数**（由 Agent 先用 file tools 确保文件存在/准备好）。

## v1 命令集（建议：围绕文档接入）

> 与 `PRD-0010` 对齐：先把“恶心文件”变成可用文本/结构化资产。

- `doc extract`
  - `--in <path>` 输入文件（pdf/docx/pptx/xlsx）
  - `--out <path>` 输出文本（建议 `.md`）
  - `--format auto|pdf|docx|pptx|xlsx`
  - `--max-chars <n>` 输出上限（超限截断并标注）
  - `--mode text|md|json`（v1 可仅 text/md）
  - 输出：
    - stdout：摘要（页数/段落数/截断信息）
    - result：`{status, extracted_chars, truncated, warnings[]}`
    - artifacts：输出文件引用

可扩展（v2+）：
- `doc ocr`（扫描件 OCR）
- `table to-csv`（xlsx/docx 表格结构化）

## Safety

- 默认“无网络”：`terminal.exec` 里的命令不得直接发起网络请求；网络访问统一走 web tools（可审计、可控）。
- 高风险动作禁止：删除/覆盖等必须通过 file tool 的二次确认流程处理（与仓库安全规则一致）。
- 输入/输出截断：stdout/stderr/result 必须有最大长度；超限写入 artifacts 文件并返回引用。

## Auditing（可审计性）

每次 `terminal.exec` 必须写入 run log（可与 `.agents/sessions/.../events.jsonl` 兼容或单独目录）：

- `timestamp`
- `command`（原始命令）
- `parsed_command`（结构化参数）
- `exit_code`
- `duration_ms`
- `artifacts[]`
- `error_code/message`（若失败）

## UX（仅作为展示，不影响协议）

- “终端”页面/面板：
  - 显示命令与输出（stdout/stderr）
  - 支持复制输出
  - 支持停止（取消当前执行）
- 与 Chat 的关系：
  - Chat 里显示“命令轨迹”摘要（对齐 PRD-0004 的进度播报）
  - 需要时可展开查看完整输出/产物

## Cross-Platform（Android / iOS）

- 命令协议与 `SKILL.md` 文档应保持一致。
- 平台实现差异：
  - Android：内置 PDFBox/POI 等实现 `doc extract`。
  - iOS：优先提供同名命令的等价实现；若暂缺某格式，返回结构化 `NotSupported(platform, format)`，并提供“导出/另存为 PDF/文本”的替代路径提示。

## Acceptance（v1 文档口径）

1. Agent 仅凭 `terminal.exec` + 现有 file tools，即可按某个 CLI 风格 SKILL 的说明书完成一次“文件解析 → 文本落盘 → 入队自动化”的闭环。
2. 未注册命令必拒绝；命令参数不合法必拒绝且错误可解释。
3. 任意一次执行都有可回放的审计记录与产物引用。

## Requirements（Req IDs）

### A. 工具面（protocol）

- REQ-0011-001：提供 `terminal_exec`（语义等价于 `terminal.exec`）工具，输入 `command/stdin/timeout_ms`，输出包含 `exit_code/stdout/stderr/result/artifacts`（按需可为空）。
- REQ-0011-002：命令协议严格解析：不允许换行/多命令串联；未注册命令必须拒绝并返回可解释错误（`UnknownCommand`）。

### B. 审计（auditing）

- REQ-0011-010：每次 `terminal_exec` 执行都必须落盘一条可回放审计记录（至少含 `timestamp/command/parsed/exit_code/duration_ms`），并在 tool output 中返回 `run_id` 与 `artifacts` 指针（如有）。

### C. 最小闭环样例

- REQ-0011-020：内置最小命令 `hello`：执行后 stdout 输出 `HELLO` 的 ASCII 图，并额外输出 `lemonhall` 字符签名（证明为程序化输出）。
- REQ-0011-021：内置 `hello-world` skill 的 `SKILL.md` 必须包含一条可执行的 `terminal_exec` 示例命令，能触发 `hello` 命令并在 Chat/终端面板里看到输出。

## Open Questions

1. 命令命名空间：`doc ...` 是否足够，还是按能力拆成 `pdf ... / office ...`？
2. 输出规范：是否强制所有命令都输出 `result` JSON（建议是）？
3. 与 Automation 的衔接：`terminal.exec` 的 artifacts 是否直接写入 `.agents/artifacts/<run_id>/...`，还是先落临时目录再由队列归档？
