# ECN-0003: Provider Retry 默认开启（对齐 Codex/OpenCode）

## 基本信息

- **ECN 编号**：ECN-0003
- **关联 PRD**：PRD-0006
- **关联 Req ID**：REQ-0006-002
- **发现阶段**：v9 执行中（需求澄清）
- **日期**：2026-02-17

## 变更原因

原 PRD-0006 设定为“重试默认不启用，需 `providerRetry.maxRetries > 0` 显式开启”。

但对齐源头项目（OpenCode/Codex 的默认体验）后，应当默认开启重试，并将默认重试次数对齐到 Codex 常见默认（至少 6 次），以显著降低移动端/代理链路下的瞬断（EOF/断流/超时）导致的随机失败率。

## 变更内容

### 原设计（PRD-0006）

- REQ-0006-002：重试必须可控，默认不启用（保持兼容），由 `providerRetry.maxRetries > 0` 显式开启。

### 新设计

- REQ-0006-002：重试必须可控，但默认启用：
  - `ProviderRetryOptions.maxRetries` 默认值设为 **6**（表示失败后最多再重试 6 次，总尝试次数最多 7 次）。
  - 退避策略采用指数退避并设置上限（实现细节见 v9 plan 与测试覆盖）。

## 影响范围

- 受影响的 Req ID：REQ-0006-002
- 受影响的 vN 计划：`docs/plan/v9-provider-retry-and-subagent-resilience.md`
- 受影响的测试：SDK runtime 重试相关单测新增/更新
- 受影响的代码文件：
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/RuntimeModels.kt`
  - `external/openagentic-sdk-kotlin/src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt`

## 处置方式

- [x] PRD 已同步更新（标注 ECN 编号）
- [x] vN 计划已同步更新
- [ ] 追溯矩阵已同步更新（在 `docs/plan/v9-index.md` 补齐）
- [ ] 相关测试已同步更新

