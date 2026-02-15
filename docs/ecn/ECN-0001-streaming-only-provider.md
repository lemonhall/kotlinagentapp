# ECN-0001：Chat 必须支持流式（OpenAI Responses API 风格）

## 基本信息

- **ECN 编号**：ECN-0001
- **关联 PRD**：PRD-0001
- **关联 Req ID**：新增（Chat 流式能力）
- **发现阶段**：v2 计划执行前（需求澄清）
- **日期**：2026-02-15

## 变更原因

实际模型供应商仅支持 **流式** 输出，并采用 **OpenAI Responses API 风格（SSE）**。原 PRD/v2 计划中“非流式最短闭环”的假设不成立，若继续按非流式实现将无法在真实环境验收。

## 变更内容

### 原设计

- Chat 真对话最短闭环优先非流式（v2 计划草案）。

### 新设计

- Chat 与 Provider 调用以 **流式（SSE）** 为主路径，非流式不作为必需能力。
- UI 需要能展示增量文本（delta）并在 completed 时收敛为最终回复文本。

## 影响范围

- 受影响的 vN 计划：
  - `docs/plan/v2-chat-real-request.md`（需改为 streaming）
- 受影响的 PRD：
  - `docs/prd/PRD-0001-kotlin-agent-app.md`（补充流式必需）

## 处置方式

- [ ] PRD 已同步更新（标注 ECN-0001）
- [ ] v2 计划已同步更新为 streaming 路径

