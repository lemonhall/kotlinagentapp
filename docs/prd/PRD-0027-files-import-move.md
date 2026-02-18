#!/usr/bin/env markdown
# PRD-0027：Files 页签导入 + 移动（inbox 导入 / 剪切粘贴）

日期：2026-02-18  
定位：在 App 内 `.agents` 工作区提供一个“可操作的文件管理最小闭环”，支持把外部文件（例如微信“用其他应用打开”）导入到 `.agents/workspace/inbox/`，并在 Files 页签内用“剪切 → 粘贴”移动到目标目录。

## Vision

用户能在手机上完成以下闭环（不依赖 adb / 不依赖电脑）：

1) 从外部应用把文件导入到 App（例如导入 `id_rsa` 私钥、日志、配置）  
2) 在 Files 页签里把文件移动到需要的位置（例如 `skills/ssh-cli/secrets/`）  

## Background

- Android / 华为 / 微信的“打开方式（用其他应用打开）”本质是隐式 Intent（`ACTION_VIEW` / `ACTION_SEND`），匹配机制依赖应用在 Manifest 中声明的 intent-filter（action + mimeType 等）。
- 目前 Files 页签可浏览/新建/删除/编辑，但缺少“导入外部文件”和“移动文件”的能力，导致配置类工作（例如 SSH 私钥）门槛较高。

## Non-Goals（本期不做）

- 不做：选择任意导入目录（本期固定导入到 `.agents/workspace/inbox/`）
- 不做：复制（Copy/Paste）与多选批量操作
- 不做：文件预览能力增强（仍复用既有 Markdown/JSON/纯文本预览）
- 不做：导出到系统相册/下载目录（SAF 写入）

## Requirements（Req IDs）

### A. 导入

- REQ-0027-001：Files 页签提供“导入”按钮，从系统文件选择器导入任意文件到 `.agents/workspace/inbox/`。
- REQ-0027-002：App 能出现在“用其他应用打开/分享”列表中（支持 `ACTION_VIEW` / `ACTION_SEND`），并在用户确认后导入文件到 `.agents/workspace/inbox/`。
- REQ-0027-003：导入时若目标文件名冲突，必须自动重命名（如追加 `_1`、`_2`），不得覆盖原文件。

### B. 移动

- REQ-0027-010：Files 页签支持对文件/目录执行“剪切”，并在目标目录执行“粘贴”完成移动。
- REQ-0027-011：移动必须仅限 `.agents` 内部路径（禁止绝对路径/`..`），并拒绝把目录移动到其自身子目录中。

## Acceptance（硬口径）

1. 导入按钮可用：导入后 `.agents/workspace/inbox/` 下出现新文件。  
2. 外部打开可用：从外部应用触发打开，App 弹出导入确认，确认后导入成功并提示。  
3. 剪切粘贴可用：长按条目“剪切”，进入其他目录后点“粘贴”（或长按空白处）完成移动。  
4. 反作弊：移动逻辑必须走工作区 API（不得通过 UI 假象），并有单测覆盖文件/目录 move。  

## Risks

- Manifest 使用 `*/*` 可能导致 App 出现在较多“打开方式”列表中；后续可通过更细粒度 mimeType 白名单优化。
- 外部文件来源多样（content:// uri），需兼容无法获得 DISPLAY_NAME 的情况（需 fallback 文件名）。

