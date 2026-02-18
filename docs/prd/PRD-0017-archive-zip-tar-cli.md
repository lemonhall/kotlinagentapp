# PRD-0017：Archive CLI（zip / tar）Pseudo Terminal Commands

日期：2026-02-18  
定位：在 `terminal_exec` 的“白名单 pseudo CLI（无 shell / 无外部进程）”框架下，暴露纯 Java 的 zip/tar 归档处理能力，用于文档/数据的打包、拆包与流转。

> 本 PRD 只定义 **v17 系列**的需求口径与验收；实现必须遵守 `paw-cli-add-workflow` 的项目规矩（命令实现拆分、注册表单一职责）。

## Vision

让 Agent 与人类都能通过 `terminal_exec` 执行类似 CLI 的归档命令，以可审计、可控风险的方式完成：

- 列出归档内容（list）
- 解压/展开归档到目录（extract）
- 从目录/文件创建归档（create）

并且：

- 不依赖外部 `zip`/`tar` 可执行程序（纯 Java）
- 默认安全（防 ZipSlip/TarSlip/压缩炸弹/越界写入）
- 输出可控（超长输出写入 artifacts）

## Non-Goals

- 不提供 shell 语义（pipe/重定向/多命令串联），仍由 `terminal_exec` 禁止。
- 不做 GUI 级“文件选择器/分享面板”的产品化体验（先走明确路径参数）。
- 不支持加密压缩包（zip AES / tar 加密）与密码输入（后续版本再规划）。
- 不支持 `7z/rar` 等非标准归档格式（后续版本再规划）。

## Dependencies（纯 Java）

v17 推荐依赖策略：

- ZIP：优先使用 JDK/Android 自带 `java.util.zip`（读写）
- TAR/TAR.GZ/TAR.BZ2/TAR.XZ：使用 Apache Commons Compress（纯 Java）

> 约束：不得引入 native 代码与外部进程执行；不得在运行时下载/执行插件。

## Project Rules（实现必须遵守：来自 paw-cli-add-workflow）

- `TerminalCommands.kt` 只能做“命令注册表/默认 registry 构建”，禁止堆实现逻辑。
- 公共协议类型只用 `TerminalCommandCore.kt`（其他命令只 import）。
- 每个顶层命令必须独立文件/目录：
  - `zip`：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/zip/ZipCommand.kt`
  - `tar`：`app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/tar/TarCommand.kt`
- `TerminalExecTool` 的 registry 初始化逻辑不改；只在 `TerminalCommands.defaultRegistry(...)` 注册新增命令。

## Command Set（v17）

统一约束：

- 所有路径参数均为 `.agents` 根目录内的相对路径（禁止 `..`、绝对路径、drive letter、canonical 出界、软链逃逸）。
- 所有会写入大量文件/覆盖内容的命令必须显式 `--confirm`，否则拒绝（`error_code="ConfirmRequired"`）。
- 所有命令输出同时提供：`stdout`（摘要）+ `result`（结构化）+（必要时）`artifacts[]`（落盘大输出）。

### zip

#### `zip list`

- 命令：`zip list --in <zip_path> [--max <n>] [--out <path>]`
- 行为：
  - 默认输出前 `--max`（缺省 200）个条目的摘要到 stdout
  - 若提供 `--out`：写入完整清单（建议 JSONL/JSON）并返回 artifact 引用
- result（最小字段）：
  - `ok: boolean`
  - `command: "zip list"`
  - `in: string`
  - `count_total: number`
  - `count_emitted: number`
  - `truncated: boolean`
  - `entries: [{name, compressed_bytes, uncompressed_bytes, is_dir, modified_time_ms}]`（可截断）

#### `zip extract`

- 命令：`zip extract --in <zip_path> --dest <dir> --confirm [--overwrite] [--max-files <n>] [--max-bytes <n>]`
- 行为（安全默认）：
  - 拒绝任何会写出 `--dest` 目录之外的 entry（ZipSlip 防护）
  - 默认不覆盖已有文件（除非 `--overwrite`）
  - 限制最大文件数与总解压字节数（压缩炸弹防护）
- result（最小字段）：
  - `ok`
  - `command: "zip extract"`
  - `in`, `dest`
  - `files_written`, `dirs_created`
  - `bytes_written`
  - `skipped: { existing, unsafe_path, too_large }`

#### `zip create`

- 命令：`zip create --src <path> --out <zip_path> --confirm [--overwrite] [--level 0-9]`
- 行为：
  - `--src` 可为目录或单文件
  - 默认不覆盖 `--out`（除非 `--overwrite`）
- result（最小字段）：
  - `ok`
  - `command: "zip create"`
  - `src`, `out`
  - `files_added`, `bytes_written`
  - `compression_level`

### tar

> tar 常见组合：`tar` + 压缩（gz/bz2/xz）。v17 允许通过 `--format` 显式指定：
> `tar|tar.gz|tar.bz2|tar.xz`（具体是否全部支持以 commons-compress 能力为准）。

#### `tar list`

- 命令：`tar list --in <tar_path> [--format <...>] [--max <n>] [--out <path>]`
- result：与 `zip list` 同口径（entries 字段可增加 `mode/uid/gid/link_name`，但可选）

#### `tar extract`

- 命令：`tar extract --in <tar_path> --dest <dir> --confirm [--format <...>] [--overwrite] [--max-files <n>] [--max-bytes <n>] [--no-symlinks]`
- 行为（安全默认）：
  - TarSlip 防护：所有 entry 解出的最终 canonical path 必须在 `--dest` 内
  - 默认拒绝 symlink/hardlink（除非明确允许；v17 建议默认拒绝并返回 `skipped.unsafe_link`）
- result：与 `zip extract` 同口径

#### `tar create`

- 命令：`tar create --src <path> --out <tar_path> --confirm [--format <...>] [--overwrite]`
- result：与 `zip create` 同口径

## Safety（必须）

### 1) 路径与越界（ZipSlip/TarSlip）

- 任何 entry 名称包含 `..`、以 `/` 开头、包含 `:`、或 resolve 后 canonical 出界 → 必须拒绝/跳过并计入 `skipped.unsafe_path`
- 绝不创建 symlink/hardlink（v17 默认），避免 link 指向 `--dest` 之外导致逃逸

### 2) 压缩炸弹

必须提供并默认启用限制：

- `--max-files`（默认例如 2000）
- `--max-bytes`（默认例如 512MB，具体由实现与设备能力调整）

超限行为必须可解释（`error_code="ArchiveTooLarge"` 或 `skipped.too_large`）。

### 3) 覆盖策略

- 默认 `--overwrite=false`，遇到已存在文件 → 跳过并计数
- 只有在 `--confirm` 存在时才允许 `--overwrite=true`（双门禁）

### 4) 审计与隐私

- 审计日志（`.agents/artifacts/terminal_exec/runs/*.json`）不得记录 stdin（本 PRD 的命令也不得把敏感内容写入 stdout/stderr/result）。

## Acceptance（v17 文档口径）

1. `terminal_exec` 新增顶层命令 `zip` 与 `tar`，并实现 `list/extract/create` 子命令（未注册命令仍返回 `UnknownCommand`）。
2. `zip extract` / `tar extract` / `zip create` / `tar create` 缺失 `--confirm` 必须拒绝（`ConfirmRequired`）。
3. 目录穿越样例必须被阻止：
   - 构造包含 `../evil.txt` 的归档，extract 时不写出 `--dest` 目录之外，并返回可解释输出。
4. 解压/创建成功后：
   - `exit_code=0`
   - `result.ok=true`
   - 产物文件真实存在（create 的 out / extract 的 dest 下文件）
5. 超长 list 输出必须可通过 `--out` + artifacts 落盘获取完整清单。
6. `.\gradlew.bat :app:testDebugUnitTest` exit code=0（需覆盖 confirm 门禁、路径越界、解压/创建 happy path）。

## Requirements（Req IDs）

### A. 命令面

- REQ-0017-001：新增 `zip list/extract/create`（纯 Java）并返回结构化 `result`。
- REQ-0017-002：新增 `tar list/extract/create`（纯 Java）并返回结构化 `result`。

### B. 安全面

- REQ-0017-010：extract/create 必须显式 `--confirm`，否则返回 `ConfirmRequired`。
- REQ-0017-011：ZipSlip/TarSlip 防护：禁止写出 `--dest` 外。
- REQ-0017-012：默认拒绝 symlink/hardlink（或明确 `--no-symlinks` 为默认）。
- REQ-0017-013：压缩炸弹限制：最大文件数/最大总字节数（默认启用）。
- REQ-0017-014：默认不覆盖；`--overwrite` 必须与 `--confirm` 同时存在才允许生效。

### C. 工程规矩

- REQ-0017-020：命令实现必须放在独立文件/目录（`commands/zip/*`、`commands/tar/*`）；`TerminalCommands.kt` 只做注册表。

