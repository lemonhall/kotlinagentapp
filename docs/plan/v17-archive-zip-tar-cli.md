# v17 Plan：Archive CLI（zip / tar）

## Goal

实现 PRD-0017 的 zip/tar 白名单命令，并把安全护栏（confirm、越界、炸弹、覆盖）固化成测试与验收口径，避免“能跑但不安全”的假交付。

## PRD Trace

- PRD-0017：REQ-0017-001 / REQ-0017-002
- PRD-0017：REQ-0017-010 ~ REQ-0017-014
- PRD-0017：REQ-0017-020

## Scope

做：
- 新增命令：
  - `zip list/extract/create`
  - `tar list/extract/create`
- 依赖策略：
  - zip：读（list/extract）用 Apache Commons Compress（支持 `--encoding` / auto 回退）；写（create）用 `java.util.zip`
  - tar：Apache Commons Compress（纯 Java）
- 安全策略（必须）：
  - extract/create 强制 `--confirm`
  - ZipSlip/TarSlip 防护
  - 默认拒绝 symlink/hardlink（v17）
  - `--max-files` / `--max-bytes` 默认启用
  - 默认不覆盖；`--overwrite` 需要 `--confirm`
- 内置 skill 文档：
  - `archive-zip`：覆盖 zip 的 list/extract/create
  - `archive-tar`：覆盖 tar 的 list/extract/create

不做：
- 加密压缩包
- 7z/rar
- shell 语义

## Acceptance（硬口径）

1. `zip extract/create` 缺失 `--confirm` 必拒绝（`ConfirmRequired`）。
2. `tar extract/create` 缺失 `--confirm` 必拒绝（`ConfirmRequired`）。
3. path traversal 归档样例解压不会写出 `--dest` 外，并返回可解释输出。
4. `--overwrite` 不带 `--confirm` 不生效（必须拒绝或忽略并可解释）。
5. list 超长输出支持 `--out`（完整清单落盘 + artifacts 引用）。
6. `.\gradlew.bat :app:testDebugUnitTest` exit code=0

## Files（规划：遵守 paw-cli-add-workflow）

- 注册表（只注册，不写实现）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalCommands.kt`
- 命令实现（拆分目录）：
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/zip/ZipCommand.kt`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/tools/terminal/commands/tar/TarCommand.kt`
- 单测（Robolectric）：
  - `app/src/test/java/com/lsl/kotlin_agent_app/agent/tools/terminal/TerminalExecToolTest.kt`
- 内置 skills：
  - `app/src/main/assets/builtin_skills/archive-zip/SKILL.md`
  - `app/src/main/assets/builtin_skills/archive-tar/SKILL.md`
  - `app/src/main/java/com/lsl/kotlin_agent_app/agent/AgentsWorkspace.kt`

## Steps（Strict）

1) TDD Red：为 zip/tar 的 confirm 门禁、越界拒绝、happy path（create→extract→list）加测试并跑红
2) TDD Green：实现 zip/tar 命令并跑绿
3) Refactor：抽取共享的安全检查（path/limits/overwrite policy）到小函数，但保持每个命令文件独立
4) 接入：注册命令 + 安装内置 skill
5) Verify：`.\gradlew.bat :app:testDebugUnitTest`

## Risks

- Android 存储空间与 I/O 性能：默认限制必须保守（max-files/max-bytes），并提供可解释错误。
- tar 链接条目（symlink/hardlink）是常见逃逸载体：v17 默认拒绝，避免安全债。
