---
name: radio-cli
description: 通过 `terminal_exec` 控制/查询电台播放器状态，以及浏览/搜索可用电台。
---

# radio-cli（Pseudo Radio CLI）

## Goal

为 App 内置 Radio 电台播放器提供"堆外控制面"（可审计、可测试）：用 `terminal_exec` 执行 `radio ...` 命令查询/控制播放状态。

## 电台库结构

电台文件存放在 `workspace/radios/` 下，按国家分目录：

```
workspace/radios/
  .countries.index.json   ← 国家列表索引（优先读取）
  .countries.meta.json    ← 国家元数据
  {CC}__{CountryName}/    ← 国家目录，如 AU__Australia/
    .stations.meta.json   ← 该国电台元数据索引（优先读取）
    {StationName}__{hash}.radio  ← 电台文件
    _STATUS.md
```

## Commands（v34）

### Help

使用工具 `terminal_exec` 执行：

- `radio --help`
- `radio help`
- `radio play --help`

期望：
- `exit_code=0`

### 状态

使用工具 `terminal_exec` 执行：

- `radio status`

期望：
- `exit_code=0`
- `result.state` 为 `idle|playing|paused|stopped|error`
- 播放电台时 `result.station.path` 为 `workspace/radios/**.radio`

### 播放控制（仅允许 radios/ 子树）

使用工具 `terminal_exec` 执行（示例）：

- `radio play --in workspace/radios/AU__Australia/ABC_Jazz_HLS__6a31da81c1.radio`
- `radio pause`
- `radio resume`
- `radio stop`

期望：
- `exit_code=0`
- `radio play` 成功后：`radio status` 的 `result.state=playing`

## 电台发现流程（必须遵守）

当用户请求播放某个电台但未给出精确路径时，按以下顺序操作：

### Step 1：确定国家目录

使用 `Read` 读取 `workspace/radios/.countries.index.json`，从中查找目标国家的目录名。

- 不要猜测目录名，不要用 Glob/List 扫描。
- 如果目标国家不在索引中，直接告知用户"当前电台库中没有该国家的电台"，停止。

### Step 2：查找电台

使用 `Read` 读取对应国家目录下的 `.stations.meta.json`，从中按关键词匹配用户想要的电台。

- 优先匹配电台名称中的关键词（如"新闻""news""classic"等）。
- 如果匹配到多个，列出候选项（最多 10 个）让用户选择。
- 如果没有匹配项，告知用户并建议相近的选项。

### Step 3：播放

拿到精确的 `.radio` 文件路径后，执行 `radio play --in <path>`。

## Rules

- 必须实际调用 `terminal_exec`，不要臆造 stdout/result/artifacts。
- `radio play` 只允许 `workspace/radios/**.radio`；越界应返回 `exit_code!=0` 且 `error_code` 可解释（如 `NotInRadiosDir/NotRadioFile/NotFound`）。
- `terminal_exec` 不支持 `;` / `&&` / `|` / 重定向；命令必须单行。
- 若工具返回 `exit_code!=0` 或包含 `error_code`，直接报告错误并停止。
- **禁止**使用 `Glob`、`List` 等文件系统工具扫描 `workspace/radios/` 目录树。所有电台发现必须通过读取索引 JSON 文件完成。
- 如果索引文件不存在或读取失败，告知用户"电台索引不可用"，不要回退到目录扫描。
