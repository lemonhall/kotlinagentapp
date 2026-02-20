# ECN-0006: DashScope ASR 异步转录默认启用 enable_words=true

## 基本信息

- **ECN 编号**：ECN-0006
- **关联 PRD**：PRD-0034
- **关联 Plan**：v40-radio-offline-transcript
- **关联模块**：ASR（DashScope / Qwen-ASR 异步调用）
- **发现阶段**：v40 真机验收（可用性/产物质量）
- **日期**：2026-02-20

## 变更原因

当前异步转录任务未显式开启 `enable_words`，服务端主要依赖 VAD 断句。对电台节目这种“连续播报/无明显停顿”的音频，常出现：

- `sentences[]` 断句粒度过粗（把很长一段合并成 1 条 sentence）
- 下游产物 `chunk_*.transcript.json` 的 segments 不利于阅读、校对与后续翻译对齐（v41）

根据 DashScope Qwen-ASR 参数说明：

- `enable_words=false`（默认）：断句粒度更粗
- `enable_words=true`：断句粒度更细（并可返回 `words` 级别信息，但本阶段不消费）

## 变更内容

### 原设计

`AliyunQwenAsrClient.submitTask` 的 `parameters.enable_words=false`。

### 新设计（本 ECN）

将异步提交请求的 `parameters.enable_words` 固定设置为 `true`：

- 不新增 UI/CLI 开关，不暴露给用户配置（本阶段始终开启）
- `parseAsrResult` 不做改动：仍只读取 `sentences[]` 的字段，多出来的 `words` 由解析逻辑忽略

## 影响范围

- 影响的行为：
  - 转录结果的断句更细，`segments` 数量可能显著增加
  - 网络返回体可能更大（但本阶段不落盘/不存储 `words`）
- 影响的风险：
  - 极端场景下响应体变大导致内存/耗时上升（后续如出现问题再评估是否需要进一步做 artifacts 化或截断策略，届时再走 ECN）

## 处置方式

- [x] 代码已修改：DashScope 异步提交参数启用 `enable_words=true`
- [x] 单元测试已通过：`:app:testDebugUnitTest`

