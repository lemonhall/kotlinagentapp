# PRD-0010：文档接入与文本抽取（PDF / DOCX / PPTX / XLSX）

日期：2026-02-17  
关联：
- `docs/prd/PRD-0008-local-first-automation.md`（Automation/Artifacts/本地工作区）
- `docs/prd/PRD-0009-schedule-trigger-and-run-queue.md`（Share Inbox / 入队）

## Vision

让用户从任意 App 分享（Share）过来的 **Word/PDF/PPT/Excel** 等文件，能在 Android 端**本地**完成“接入 → 抽取可用文本 → 落盘为资产（Inbox/Artifact）”，从而可被 Skills/Automation 复用；对抽取失败或超大文件提供明确的降级路径与安全提示。

一句话：**把“文件附件”变成“可跑流程的本地输入”。**

## Background / Problem

- Office/PDF 文件在移动端解析成本高（体积、内存、格式差异、扫描件）。
- 如果不能把“分享来的文件”转成可用文本，很多研究/产出/自动化场景无法闭环。
- 本项目强调 local-first 与 no-server：默认不依赖远端转换服务（除非用户显式授权）。

## Scope（v1）

做（优先 OOXML）：
- PDF：抽取文本（支持按页分段）。
- DOCX / PPTX / XLSX：抽取文本（以段落/幻灯片/单元格文本为主）。
- Share 接入：支持从系统分享接收文件 URI，并复制到 App 私有目录形成可追溯资产。
- 产物落盘：生成“原文件副本 + 抽取文本 + 元信息 + 抽取日志”。

不做（v1 明确排除）：
- 高保真排版还原（样式、图表、复杂布局、批注修订等）。
- OCR（扫描 PDF/图片文字识别）默认不做；仅给出 v2+ 方向。
- 旧二进制格式：`.doc/.xls/.ppt`（v1 直接提示用户另存为 `.docx/.xlsx/.pptx` 或导出 PDF）。
- 自动把文件上传到 LLM（任何“出端”行为必须显式确认）。

## Constraints & Principles

- **Local-first**：默认仅在手机本地处理与落盘；抽取结果作为 `.agents/*` 资产可复用/可导出。
- **Best-effort**：抽取能成功就成功；失败要“可解释 + 可恢复 + 可替代路径”。
- **Resource-aware**：对文件大小/页数/幻灯片数做硬上限与截断策略，避免 OOM/卡死。
- **Security**：文件可能包含敏感信息；不得自动外传；日志需截断/打码。

## Data & Storage（建议约定）

> 与 PRD-0008/0009 的 Inbox/Artifacts 体系兼容。

- 共享接入快照（Inbox）：
  - `.agents/inbox/<inbox_id>.json`
  - `.agents/inbox/<inbox_id>/original/<filename>`（复制后的原文件）
  - `.agents/inbox/<inbox_id>/extracted/text.md`（抽取文本）
  - `.agents/inbox/<inbox_id>/extracted/extracted.json`（结构化抽取结果，可选）
  - `.agents/inbox/<inbox_id>/extracted/extract_log.json`（抽取摘要/错误，必有）

`inbox.json` 最小字段建议：
- `inbox_id`
- `created_at`
- `source`: `share|agent|file_picker`
- `display_name`
- `mime_type`
- `size_bytes`
- `sha256`（对 original 计算，便于去重与追溯）
- `original_path`（相对路径）
- `extracted_text_path`（若成功）
- `extraction_status`: `succeeded|failed|skipped|truncated`

## Extraction Strategy（v1 建议）

### Format Support Matrix

| 格式 | v1 支持 | 目标输出 | 主要风险 | 降级 |
|---|---:|---|---|---|
| PDF | ✅ | 按页拼接的纯文本 | 扫描件无文本层 | 提示“需要 OCR（v2）/手动转文本” |
| DOCX | ✅ | 段落/表格文本 | 大文件/复杂对象 | 截断/仅抽取前 N 段 |
| PPTX | ✅ | 按幻灯片抽取文本 | 图形/图表文本丢失 | 仅抽取可见文本框 |
| XLSX | ✅ | 单元格文本/公式（可选） | 规模大/OOM | 仅抽取前 N 行列/工作表 |
| DOC/XLS/PPT | ❌ | - | 旧格式 | 引导另存为 OOXML/PDF |

### Libraries（候选）

> 这里只做“文档落盘草案”，版本与引入方式在实现阶段再锁定。

- PDF：`pdfbox-android`（文本抽取/拆页等，偏重但成熟）。
- OOXML：Apache POI（优先 `poi-ooxml-lite`，并做强限制与后台执行）。
- 预览（非核心）：AndroidX PDF Viewer（alpha）或第三方 PDF 渲染，仅用于“查看”，不影响抽取主链路。
- 商业兜底（可选，不作为 v1 依赖）：Aspose/Apryse 等（省心但付费与体积成本高）。

## UX（v1）

### Flow：Share 文件 → Inbox → 可入队

1. 用户从任意 App 分享一个文件到本 App。
2. App 创建 Inbox 条目并复制原文件到私有目录（确保 URI 失效后仍可追溯）。
3. 后台开始抽取（显示进度/已用时，复用 PRD-0004 的“进度播报”理念）。
4. 抽取成功：Inbox 展示文本预览 + “加入队列/立即运行”按钮（对接 PRD-0009 的 Run Queue）。
5. 抽取失败：展示原因（如扫描件/格式不支持/超限），提供替代操作：
   - “仅作为附件保存并入队”（让 Skill 走其他策略，如让用户手动复制关键段落）
   - “提示另存为 docx/pdf 再分享”

## Requirements（Req IDs）

### A. Share 接入与落盘

- REQ-0010-001：支持从 Share 接收 `content://` URI 的文件，并复制到 App 私有目录。
- REQ-0010-002：复制后生成 `inbox.json`（含 mime/size/hash/original_path）。
- REQ-0010-003：支持“同一文件去重提示”（hash 相同可复用已有 extracted）。

### B. 抽取（Best-effort）

- REQ-0010-010：对 PDF/DOCX/PPTX/XLSX 进行文本抽取并生成 `text.md`（成功则写入 `extracted_text_path`）。
- REQ-0010-011：抽取失败必须生成 `extract_log.json`，包含 `error_code`、简要 `message`、处理耗时、截断信息。
- REQ-0010-012：资源限制：
  - 文件大小上限（例如 20–50MB 可配置，超限默认跳过抽取）；
  - 文本长度上限（超限截断并标注 `truncated`）；
  - 页数/幻灯片/工作表/单元格采样上限（超限截断）。

### C. 与自动化/队列衔接

- REQ-0010-020：Inbox 条目可直接被 `automation.enqueue` 引用（通过 `inbox_id` 或 `extracted_text_path`）。
- REQ-0010-021：Automation 步骤可声明对输入的“抽取依赖”：未抽取成功则走降级分支（v1 仅提示与阻止执行亦可）。

### D. 安全与出端授权

- REQ-0010-030：默认不把原文件内容上传到任何远端服务（包括 LLM）；如未来提供“云端转换/上传分析”，必须显式二次确认，并清晰提示数据出端。

## Future（v2+）

- OCR：对扫描 PDF/图片引入可选 OCR（如 ML Kit Text Recognition / Tesseract），并把 OCR 结果落盘为独立 artifact（标注来源与置信度）。
- 结构化抽取增强：对表格（xlsx/docx 表格）输出更可用的 CSV/JSON。
- “本地可编程运行时”支持：允许用户写脚本处理 extracted.json（默认禁用，受沙箱/确认保护）。

