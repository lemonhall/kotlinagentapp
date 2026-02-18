# PRD-0016：Terminal Tab（命令历史 + 人类输入执行）

日期：2026-02-18  
定位：为 `terminal_exec` 的“伪终端/白名单 CLI”提供一个真实的终端观感页面：记录并展示所有执行过的命令（包括 Agent 与人类手动执行），并允许人类在 App 内输入命令执行（未来可扩展为交互式终端）。

## Vision

在 App 底部导航栏新增 `Terminal` 页签，提供：

1) **历史记录**：展示所有 `terminal_exec` 执行过的命令（时间、命令、exit code、stdout/stderr 摘要、run_id）。  
2) **手动执行**：人类可输入命令（以及可选 stdin），点击运行，立即看到输出并自动进入历史。  

## Non-Goals（v16 范围外）

- 交互式 TTY（逐字符输入、实时流式输出、光标控制）。
- shell 语义（pipe/重定向/多命令串联）仍由 `terminal_exec` 严格禁止。
- 对非 `terminal_exec` 的工具执行历史做统一展示（v16 只做终端）。

## Data Source（审计落盘）

`terminal_exec` 每次执行会落盘审计文件：

- `.agents/artifacts/terminal_exec/runs/<run_id>.json`

v16 需要该审计包含（至少）：

- `timestamp_ms`
- `command`
- `argv`
- `exit_code`
- `duration_ms`
- `stdout`（截断后）
- `stderr`（截断后）
- `error_code` / `error_message`（若失败）
- `artifacts[]`（若有）

> 重要：审计不得包含 stdin（避免泄露 token/密码）。

## UX（v16）

- 顶部：命令输入框 + Run 按钮
- 可选：展开一个 stdin 输入框（默认折叠）
- 下方：历史列表（最新在上）
  - 每行：时间、exit code、命令
  - 点开：弹窗显示 stdout/stderr（可复制），并展示 artifacts 路径（可复制）

## Safety

- 手动执行仍走同一套白名单命令与解析约束（`terminal_exec`），因此不会执行外部进程。
- UI 允许输入任意字符串，但被拒绝时要把 `error_code/error_message` 直接展示给用户。

## Acceptance

1. 底部导航栏新增 `Terminal` 页签，进入后显示历史列表。
2. 任意一次 `terminal_exec`（不论由 Agent 还是人类触发）都会出现在历史列表中。
3. 在 Terminal 页签输入 `hello` 点击 Run：
   - UI 显示 stdout 包含 `HELLO` 与 `lemonhall`
   - 历史新增一条记录
4. `terminal_exec` 审计文件不包含 stdin。
5. `.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Requirements（Req IDs）

- REQ-0016-001：新增 Terminal 页签（BottomNavigation + NavigationGraph）。
- REQ-0016-002：Terminal 页签读取 `.agents/artifacts/terminal_exec/runs/*.json` 并展示历史。
- REQ-0016-003：Terminal 页签支持手动执行 `terminal_exec`（可选 stdin），并展示输出。
- REQ-0016-004：扩展 `terminal_exec` 审计文件包含 stdout/stderr（截断后），且不写入 stdin。
- REQ-0016-010：单测覆盖审计包含 stdout/stderr 且不含 stdin（最小断言）。

